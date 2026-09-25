package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.editii.scriptorium.VersionProperties;

import java.util.Map;

/**
 * The public counterpart of the admin-gated /api/admin/version
 * (AdminRestController): the precise version of the project this
 * service is running. Deliberately outside /api/admin (SecurityConfig
 * falls through to permitAll for anything not explicitly listed) so
 * that "is what's deployed the latest stable?" is answerable with one
 * unauthenticated GET - the same contract biblioteca-nestjs's /api/info
 * and biblioteca-reader's /api/info follow. Read-only, build identity
 * only (version, build number/date, git commit/branch, build machine):
 * the same fields /api/admin/version already exposes behind the gate,
 * never addresses or secrets.
 */
@RestController
@RequestMapping("/api/info")
@RequiredArgsConstructor
public class InfoRestController {

    final Environment environment;
    final VersionProperties versionProperties;

    @GetMapping
    public Map<String, String> info() {
        final String gitLatestCommit = this.versionProperties.getGitLatestCommit();
        return Map.of("appName", this.environment.getProperty("app.name"),
                "version", this.versionProperties.getAppVersion(),
                "buildNumber", this.versionProperties.getBuildNumber(),
                "buildDate", this.versionProperties.getBuildDate(),
                "git_latest_commit", gitLatestCommit.substring(0, Integer.min(6, gitLatestCommit.length())),
                "git_branch", this.versionProperties.getGitBranch(),
                "buildMachine", this.versionProperties.getBuildMachine()
        );
    }
}
