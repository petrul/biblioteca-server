package ro.editii.scriptorium.web;


import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.rest.core.annotation.RestResource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ro.editii.scriptorium.Globals;
import ro.editii.scriptorium.dao.TeiFileRepository;
import ro.editii.scriptorium.scheduled.NoWriter;
import ro.editii.scriptorium.service.AdminService;
import ro.editii.scriptorium.service.TeiFileDbService;
import ro.editii.scriptorium.tei.TeiRepo;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

@RestController
@CrossOrigin
@RequestMapping("/admin/_backend")
@Log
@RequiredArgsConstructor
@Hidden
public class AdminController {

    final TeiRepo teiRepo;
    final TeiFileRepository teiFileRepository;
    final TeiFileDbService teiFileDbService;
    final AdminService adminService;

    @GetMapping("/ping")
    public @ResponseBody String ping() {
        return "OK";
    }


    @PostMapping("/teifile-reload/{teiRepo}/{teiFile}")
    public void teiFileReload(@PathVariable String teiRepo, @PathVariable String teiFile) {
        log.info(String.format("reload asked for teirepo [%s] / [%s]", teiRepo, teiFile));
        this.adminService.reimportFresherTeis(new NoWriter());
    }

    @GetMapping("/teirepo")
    public String listTeiRepo(Model model) {
        List<String> teis = this.teiRepo.list();
        model.addAttribute("teidir", this.teiRepo.toString());
        model.addAttribute("teis", teis);

        return "admin/teirepo";
    }


    @PostMapping("/reinit")
    @Transactional
    public void reinit(HttpServletResponse httpServletResponse) {
        try (Globals.ImportLock ignored = Globals.lockImports()) {
            try {
                PrintWriter writer = httpServletResponse.getWriter();
                this.adminService.destroyAllExistingAndReimportAllTeis(writer, true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
