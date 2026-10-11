package ro.editii.scriptorium.graphql;

import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeRuntimeWiring;
import graphql.scalars.ExtendedScalars;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ro.editii.scriptorium.security.AdminUsers;

import java.beans.Introspector;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only GraphQL facade over the existing JPA metamodel and repositories. */
@Configuration(proxyBeanMethods = false)
public class GraphQLJpaConfig {
    private static final Set<String> PRIVATE_TYPES = Set.of(
            "AppUser", "UserPreference", "ReadingProgress", "DivCollection", "DivCollectionItem");
    private static final Set<String> SECRETS = Set.of("passwordHash", "googleSub");

    @Bean
    DataFetcherExceptionResolver graphqlInputErrors() {
        return DataFetcherExceptionResolver.forSingleError((error, env) ->
                error instanceof IllegalArgumentException
                        ? graphql.GraphqlErrorBuilder.newError(env).errorType(ErrorType.BAD_REQUEST)
                                .message(error.getMessage()).build() : null);
    }

    @Bean
    GraphQlSourceBuilderCustomizer jpaGraphQl(EntityManagerFactory emf, ApplicationContext context,
            PlatformTransactionManager transactionManager, AdminUsers admins) {
        var repositories = new Repositories(context);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_UNCOMMITTED);
        var entities = emf.getMetamodel().getEntities().stream()
                .sorted(java.util.Comparator.comparing(EntityType::getName)).toList();
        var polymorphic = entities.stream().filter(base -> entities.stream().anyMatch(sub ->
                base != sub && base.getJavaType().isAssignableFrom(sub.getJavaType())))
                .map(EntityType::getName).collect(java.util.stream.Collectors.toSet());
        var schema = new StringBuilder("scalar Long\nextend type Query {\n");
        for (var entity : entities) {
            // Fail at startup rather than silently leave an entity's queries unresolved.
            repository(repositories, context, entity);
            var name = Introspector.decapitalize(entity.getName());
            schema.append(name).append("(id: ID!): ").append(outputType(entity.getJavaType(), polymorphic)).append('\n')
                    .append(name).append("List(page: Int = 0, size: Int = 20, filter: ")
                    .append(entity.getName()).append("Filter): [").append(outputType(entity.getJavaType(), polymorphic)).append("!]!\n");
        }
        schema.append("}\n");
        for (var entity : entities) {
            if (polymorphic.contains(entity.getName())) {
                schema.append("interface ").append(entity.getName()).append("Node {\n");
                appendFields(schema, entity, polymorphic);
                schema.append("}\n");
            }
            schema.append("type ").append(entity.getName());
            var ancestors = entities.stream().filter(base -> polymorphic.contains(base.getName())
                    && base.getJavaType().isAssignableFrom(entity.getJavaType()))
                    .map(base -> base.getName() + "Node").toList();
            if (!ancestors.isEmpty()) schema.append(" implements ").append(String.join(" & ", ancestors));
            schema.append(" {\n");
            appendFields(schema, entity, polymorphic);
            schema.append("}\ninput ").append(entity.getName()).append("Filter {\n");
            attributes(entity).stream().filter(GraphQLJpaConfig::filterable).forEach(attr ->
                    schema.append(attr.getName()).append(": ").append(graphQlType(attr)).append('\n'));
            schema.append("}\n");
        }
        return builder -> builder
                .configureTypeDefinitions(registry -> registry.merge(new SchemaParser().parse(schema.toString())))
                .instrumentation(List.of(new MaxQueryDepthInstrumentation(12)))
                .configureRuntimeWiring(wiring -> {
                    wiring.scalar(ExtendedScalars.GraphQLLong);
                    for (var name : polymorphic)
                        wiring.type(TypeRuntimeWiring.newTypeWiring(name + "Node").typeResolver(env ->
                                env.getSchema().getObjectType((String) env.<Map<String, Object>>getObject().get("__jpaType"))));
                    var queries = TypeRuntimeWiring.newTypeWiring("Query");
                    for (var entity : entities) {
                        var repo = repository(repositories, context, entity);
                        var name = Introspector.decapitalize(entity.getName());
                        graphql.schema.DataFetcher<Object> single = env -> transaction.execute(status -> {
                            authorize(entity, admins);
                            var id = entity.getIdType().getJavaType() == Long.class
                                    ? Long.valueOf(env.<String>getArgument("id")) : env.getArgument("id");
                            return project(repo.findById(id).orElse(null), env.getSelectionSet());
                        });
                        graphql.schema.DataFetcher<Object> many = env -> transaction.execute(status -> {
                            authorize(entity, admins);
                            int page = env.getArgument("page"), size = env.getArgument("size");
                            if (page < 0 || size < 1 || size > 100)
                                throw new IllegalArgumentException("page must be >= 0 and size between 1 and 100");
                            var id = entity.getSingularAttributes().stream().filter(SingularAttribute::isId)
                                    .findFirst().orElseThrow().getName();
                            return repo.findAll(example(entity, env), PageRequest.of(page, size, Sort.by(id)))
                                    .getContent().stream().map(value -> project(value, env.getSelectionSet())).toList();
                        });
                        queries.dataFetcher(name, single).dataFetcher(name + "List", many);
                        if (entity.getName().equals("Author"))
                            queries.dataFetcher("authors", many).dataFetcher("authorById", single);
                        wiring.type(TypeRuntimeWiring.newTypeWiring(entity.getName())
                                .defaultDataFetcher(env -> {
                                    authorize(entity, admins);
                                    return env.<Map<String, Object>>getSource().get(env.getField().getResultKey());
                                }));
                    }
                    wiring.type(queries);
                });
    }

    @SuppressWarnings("unchecked")
    private static JpaRepository<Object, Object> repository(Repositories repositories,
            ApplicationContext context, EntityType<?> entity) {
        // Bean overrides and decorators may not retain Spring Data factory metadata.
        return (JpaRepository<Object, Object>) repositories.getRepositoryFor(entity.getJavaType())
                .orElseGet(() -> context.getBeanProvider(org.springframework.core.ResolvableType
                        .forClassWithGenerics(JpaRepository.class, entity.getJavaType(),
                                entity.getIdType().getJavaType())).getObject());
    }

    private static List<Attribute<?, ?>> attributes(EntityType<?> entity) {
        return entity.getAttributes().stream().filter(attr -> !SECRETS.contains(attr.getName()))
                .filter(attr -> graphQlType(attr) != null)
                .sorted(java.util.Comparator.comparing(Attribute::getName))
                .<Attribute<?, ?>>map(attr -> attr).toList();
    }

    private static String outputType(Class<?> type, Set<String> polymorphic) {
        return type.getSimpleName() + (polymorphic.contains(type.getSimpleName()) ? "Node" : "");
    }

    private static void appendFields(StringBuilder schema, EntityType<?> entity, Set<String> polymorphic) {
        attributes(entity).forEach(attr -> {
            String type = graphQlType(attr);
            if (attr instanceof PluralAttribute<?, ?, ?> plural)
                type = "[" + outputType(plural.getElementType().getJavaType(), polymorphic) + "!]";
            else if (attr.isAssociation()) type = outputType(attr.getJavaType(), polymorphic);
            schema.append(attr.getName()).append(": ").append(type).append('\n');
        });
    }

    private static String graphQlType(Attribute<?, ?> attr) {
        if (attr instanceof PluralAttribute<?, ?, ?> plural)
            return plural.getElementType().getPersistenceType() == jakarta.persistence.metamodel.Type.PersistenceType.ENTITY
                    ? "[" + plural.getElementType().getJavaType().getSimpleName() + "!]" : null;
        if (attr.isAssociation()) return attr.getJavaType().getSimpleName();
        if (attr instanceof SingularAttribute<?, ?> singular && singular.isId()) return "ID";
        var type = attr.getJavaType();
        if (type == Long.class || type == long.class) return "Long";
        if (type == Integer.class || type == int.class) return "Int";
        if (type == Double.class || type == double.class || type == Float.class || type == float.class) return "Float";
        if (type == Boolean.class || type == boolean.class) return "Boolean";
        if (type == String.class || type.isEnum() || java.util.Date.class.isAssignableFrom(type)
                || java.time.temporal.TemporalAccessor.class.isAssignableFrom(type)) return "String";
        // Binary LOBs and transient DOM/XML machinery aren't GraphQL values.
        return null;
    }

    private static boolean filterable(Attribute<?, ?> attr) {
        var type = attr.getJavaType();
        return !attr.isAssociation() && !(attr instanceof PluralAttribute)
                && !java.util.Date.class.isAssignableFrom(type)
                && !java.time.temporal.TemporalAccessor.class.isAssignableFrom(type);
    }

    private static Example<Object> example(EntityType<?> entity, DataFetchingEnvironment env) {
        Map<String, Object> filter = env.getArgument("filter");
        if (filter == null) filter = Map.of();
        var probe = BeanUtils.instantiateClass(entity.getJavaType());
        var properties = PropertyAccessorFactory.forBeanPropertyAccess(probe);
        filter.forEach((name, value) -> properties.setPropertyValue(propertyName(properties, name), value));
        var supplied = filter.keySet();
        // Ignore constructor/primitive defaults, including timestamps and Role.USER.
        var ignored = entity.getAttributes().stream().map(Attribute::getName)
                .filter(name -> !supplied.contains(name)).toArray(String[]::new);
        return Example.of(probe, ExampleMatcher.matchingAll().withIgnorePaths(ignored));
    }

    private static String propertyName(org.springframework.beans.BeanWrapper properties, String name) {
        // Lombok uses isFavorites/setFavorites for the persisted isFavorites field.
        return !properties.isReadableProperty(name) && name.startsWith("is")
                ? Introspector.decapitalize(name.substring(2)) : name;
    }

    private static void authorize(EntityType<?> entity, AdminUsers admins) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (PRIVATE_TYPES.contains(entity.getName()) && (auth == null || !auth.isAuthenticated()
                || !admins.isAdmin(auth.getName())))
            throw new AccessDeniedException("Administrator access required for " + entity.getName());
    }

    /** Materialize only selected fields while the repository transaction is still open. */
    private static Object project(Object value, DataFetchingFieldSelectionSet selection) {
        if (value == null) return null;
        if (value instanceof Collection<?> collection)
            return collection.stream().map(item -> project(item, selection)).toList();
        if (selection.getImmediateFields().isEmpty()) return value;
        var properties = PropertyAccessorFactory.forBeanPropertyAccess(value);
        var result = new LinkedHashMap<String, Object>();
        var typeName = org.hibernate.Hibernate.getClass(value).getSimpleName();
        result.put("__jpaType", typeName);
        for (var field : selection.getImmediateFields()) {
            if (!field.getObjectTypeNames().contains(typeName)) continue;
            if (field.getName().equals("__typename")) continue;
            result.put(field.getResultKey(), project(properties.getPropertyValue(propertyName(properties, field.getName())), field.getSelectionSet()));
        }
        return result;
    }
}
