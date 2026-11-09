package ro.editii.scriptorium.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the MCP tool set (ro.editii.scriptorium.mcp) with the Spring AI
 * MCP server autoconfiguration: streamable-HTTP JSON-RPC at /mcp, guarded by
 * the internal API token like /graphql (see SecurityConfig). Tools are
 * registered once, explicitly - the annotation scanner stays off
 * (spring.ai.mcp.server.annotation-scanner.enabled=false) so no @Tool method
 * can be registered a second time by accident.
 */
@Configuration(proxyBeanMethods = false)
public class McpConfig {

    @Bean
    ToolCallbackProvider mcpToolCallbacks(McpSearchTools search, McpCatalogTools catalog,
            McpTextTools texts, McpGraphqlTools graphql) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(search, catalog, texts, graphql)
                .build();
    }
}
