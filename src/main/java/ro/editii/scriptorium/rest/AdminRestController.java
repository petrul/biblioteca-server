package ro.editii.scriptorium.rest;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.rest.core.annotation.RestResource;
import org.springframework.web.bind.annotation.*;
import ro.editii.scriptorium.VersionProperties;
import ro.editii.scriptorium.dto.TeiRepoDto;
import ro.editii.scriptorium.scheduled.NoWriter;
import ro.editii.scriptorium.service.AdminService;
import ro.editii.scriptorium.tei.CombinedTeiRepo;
import ro.editii.scriptorium.tei.TeiRepo;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Hidden
public class AdminRestController {

    final TeiRepo teiRepo;
    final AdminService adminService;
    final Environment environment;
    final VersionProperties versionProperties;

    @GetMapping("/version")
    public @ResponseBody Map version() {
        final String appname = this.environment.getProperty("app.name");
        final String gitLatestCommit = this.versionProperties.getGitLatestCommit();
        return Map.of("appName", appname,
                "version", this.versionProperties.getAppVersion(),
                "buildNumber", this.versionProperties.getBuildNumber(),
                "buildDate", this.versionProperties.getBuildDate(),
                "git_latest_commit", gitLatestCommit.substring(0, Integer.min(6, gitLatestCommit.length())),
                "git_branch", this.versionProperties.getGitBranch(),
                "buildMachine", this.versionProperties.getBuildMachine()
        );
    }

    @GetMapping("/teirepos")
    public List<TeiRepoDto> listTeiRepos() {
        CombinedTeiRepo combinedTeiRepo = (CombinedTeiRepo) this.teiRepo;
        return combinedTeiRepo.getRepos().stream()
                .map( it -> {
                    return TeiRepoDto.builder()
                            .name(it.getName())
                            .files(it.list())
                            .build();
                })
                .collect(Collectors.toList());
    }


    @PostMapping("/teirepos/reimportFresher")
    public void teiReposReimportFresher() {
        this.adminService.reimportFresherTeis(new NoWriter());
    }


    @PostMapping("/teirepos/reimportAll")
    public void teiReposReimportAll() {
        this.adminService.reimportAllTeis(new NoWriter());
    }

    @PostMapping("/teirepos/reimport")
    public void postTeireposReimport(@RequestParam String file) {
            this.adminService.reimportFile(file, new NoWriter());
    }


    @GetMapping("/teirepos/reimport")
    @ResponseBody
    public String getTeiReposReimport(@RequestParam String file) {
        return "works " + file;
    }


    @PostMapping("/teirepos/forceReimportAll")
    public void teiReposForcefullyReimportAll() {
        this.adminService.destroyAllExistingAndReimportAllTeis(new NoWriter(), true);
    }

    @PostMapping("/teirepos/pruneRemoved")
    public void teiReposPruneRemoved() {
        this.adminService.pruneRemovedTeis(new NoWriter());
    }

    @PostMapping("/lucene/reindex")
    @ResponseBody
    public Map<String, Integer> reindexLucene() {
        return Map.of("indexed", this.adminService.reindexLucene());
    }

}
