package ro.editii.scriptorium.security.google

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ro.editii.scriptorium.collection.DivCollectionService
import ro.editii.scriptorium.dao.AppUserRepository
import ro.editii.scriptorium.model.AppUser

import java.util.concurrent.atomic.AtomicReference

import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

/** Plain unit tests: token verification, persistence and collection creation are collaborators. */
class GoogleAuthServiceTest {

    AppUserRepository appUserRepository
    DivCollectionService divCollectionService
    GoogleIdTokenVerifier tokenVerifier
    GoogleProfileClient googleProfileClient
    GoogleAuthService service

    @BeforeEach
    void setUp() {
        this.appUserRepository = mock(AppUserRepository)
        this.divCollectionService = mock(DivCollectionService)
        this.tokenVerifier = mock(GoogleIdTokenVerifier)
        this.googleProfileClient = mock(GoogleProfileClient)
        this.service = new GoogleAuthService(appUserRepository, divCollectionService, tokenVerifier, googleProfileClient)

        when(appUserRepository.save(any(AppUser))).thenAnswer { invocation ->
            final user = invocation.getArgument(0, AppUser)
            if (user.id == null) user.id = 1L
            return user
        }
    }

    @Test
    void createsANewAccountOnFirstGoogleSignIn() {
        final claims = new GoogleClaims("google-sub-1", "alice@example.com", "Alice", "https://example.com/alice.jpg", true)
        when(tokenVerifier.verify("credential")).thenReturn(claims)
        when(appUserRepository.findByGoogleSub(claims.sub())).thenReturn(Optional.empty())
        when(appUserRepository.existsByUsername(claims.email())).thenReturn(false)

        final user = service.signIn("credential")

        assert user.id == 1L
        assert user.googleSub == claims.sub()
        assert user.username == claims.email()
        assert user.avatarUrl == claims.picture()
        assert user.passwordHash == null
        assert user.role == AppUser.Role.USER
        verify(appUserRepository).save(user)
        verify(divCollectionService).createFavoritesIfMissing(user)
    }

    @Test
    void linksAnExistingLegacyEmailAccountAndStoresItsGoogleAvatar() {
        final claims = new GoogleClaims("google-sub-petru", "petru@scriptorium.ro", "Petru", "https://example.com/petru.jpg", true)
        final legacy = AppUser.builder()
                .id(7L)
                .username(claims.email())
                .role(AppUser.Role.ADMIN)
                .build()
        when(tokenVerifier.verify("credential")).thenReturn(claims)
        when(appUserRepository.findByGoogleSub(claims.sub())).thenReturn(Optional.empty())
        when(appUserRepository.findByUsername(claims.email())).thenReturn(Optional.of(legacy))
        when(appUserRepository.save(any(AppUser))).thenAnswer { invocation -> invocation.getArgument(0, AppUser) }

        final user = service.signIn("credential")

        assert user.is(legacy)
        assert user.googleSub == claims.sub()
        assert user.avatarUrl == claims.picture()
        assert user.role == AppUser.Role.ADMIN
        verify(appUserRepository).save(legacy)
        verify(divCollectionService, never()).createFavoritesIfMissing(any(AppUser))
    }

    @Test
    void refreshesTheAvatarOnRepeatSignInWhenGoogleReturnsADifferentPicture() {
        final firstClaims = new GoogleClaims("google-sub-3", "dora@example.com", "Dora", "https://example.com/old.jpg", true)
        final secondClaims = new GoogleClaims("google-sub-3", "dora@example.com", "Dora", "https://example.com/new.jpg", true)
        final saved = new AtomicReference<AppUser>()
        when(tokenVerifier.verify("credential")).thenReturn(firstClaims, secondClaims)
        when(appUserRepository.findByGoogleSub(firstClaims.sub())).thenAnswer { Optional.ofNullable(saved.get()) }
        when(appUserRepository.save(any(AppUser))).thenAnswer { invocation ->
            final user = invocation.getArgument(0, AppUser)
            if (user.id == null) user.id = 3L
            saved.set(user)
            return user
        }
        when(appUserRepository.existsByUsername(firstClaims.email())).thenReturn(false)

        service.signIn("credential")
        final second = service.signIn("credential")

        assert second.avatarUrl == "https://example.com/new.jpg"
        verify(appUserRepository, times(2)).save(any(AppUser))
    }

    @Test
    void reusesTheSameAccountOnRepeatSignIn() {
        final claims = new GoogleClaims("google-sub-2", "bob@example.com", "Bob", null, true)
        final saved = new AtomicReference<AppUser>()
        when(tokenVerifier.verify("credential")).thenReturn(claims)
        when(appUserRepository.findByGoogleSub(claims.sub())).thenAnswer {
            Optional.ofNullable(saved.get())
        }
        when(appUserRepository.save(any(AppUser))).thenAnswer { invocation ->
            final user = invocation.getArgument(0, AppUser)
            user.id = 2L
            saved.set(user)
            return user
        }

        final first = service.signIn("credential")
        final second = service.signIn("credential")

        assert second.is(first)
        verify(appUserRepository, times(1)).save(any(AppUser))
        verify(divCollectionService, times(1)).createFavoritesIfMissing(first)
    }

    @Test
    void disambiguatesAnExistingUsername() {
        final claims = new GoogleClaims("abcdef123456", "carol@example.com", "Carol", null, true)
        when(tokenVerifier.verify("credential")).thenReturn(claims)
        when(appUserRepository.findByGoogleSub(claims.sub())).thenReturn(Optional.empty())
        when(appUserRepository.findByUsername(claims.email())).thenReturn(Optional.empty())
        when(appUserRepository.existsByUsername(claims.email())).thenReturn(true)

        final user = service.signIn("credential")

        assert user.username == "carol@example.com_abcdef"
    }

    @Test
    void rejectsAnInvalidCredential() {
        when(tokenVerifier.verify("bad credential"))
                .thenThrow(new IllegalArgumentException("invalid Google credential"))

        final ex = shouldFail { service.signIn("bad credential") }

        assert ex.message.contains("invalid Google credential")
        verifyNoInteractions(appUserRepository, divCollectionService)
    }

    @Test
    void retrievesAndStoresThePictureThroughTheApprovedGoogleProfileFlow() {
        final user = AppUser.builder()
                .id(42L)
                .username('petru@scriptorium.ro')
                .googleSub('google-sub-petru')
                .role(AppUser.Role.ADMIN)
                .build()
        when(appUserRepository.findByUsername(user.username)).thenReturn(Optional.of(user))
        when(googleProfileClient.fetch('oauth-access-token'))
                .thenReturn(new GoogleProfile(user.googleSub, user.username, 'https://lh3.googleusercontent.com/petru'))

        final result = service.refreshProfile('oauth-access-token', user.username)

        assert result.is(user)
        assert result.avatarUrl == 'https://lh3.googleusercontent.com/petru'
        verify(googleProfileClient).fetch('oauth-access-token')
        verify(appUserRepository).save(user)
    }

    @Test
    void refusesAProfileBelongingToAnotherSignedInEmail() {
        final user = AppUser.builder()
                .username('petru@scriptorium.ro')
                .googleSub('google-sub-petru')
                .role(AppUser.Role.ADMIN)
                .build()
        when(googleProfileClient.fetch('oauth-access-token'))
                .thenReturn(new GoogleProfile('other-sub', 'other@example.com', 'https://example.com/other.jpg'))

        final ex = shouldFail { service.refreshProfile('oauth-access-token', user.username) }

        assert ex.message.contains('does not match')
        verifyNoInteractions(appUserRepository)
    }

    private static Exception shouldFail(Closure closure) {
        try {
            closure.call()
        } catch (Exception e) {
            return e
        }
        throw new AssertionError("expected an exception but none was thrown")
    }
}
