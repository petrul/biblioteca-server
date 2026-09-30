package ro.editii.scriptorium.search.content;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.client.RestTemplate;
import ro.editii.scriptorium.client.BibliotecaClient;

/**
 * this is a {@link ContentResolver} that maps a downloads-dl path to the TB site url
 */
@RequiredArgsConstructor @Deprecated
@Log4j2
public class TextbaseDl2TextbaseContentResolver implements ContentResolver {

    final BibliotecaClient textbaseClient;

    /**
     * get the content identified by the given id
     */
    public String resolve(String id) {

        String fragm = null;
        Integer nrPara = null;
        Integer nrSentence = null;

        if (id.contains("#")) {
            final var fragms = id.split("#");
            id = fragms[0];
            fragm = fragms[1];
            final var pathElems = id.split("\\/");
            id = pathElems[pathElems.length - 1];
            id = id.replaceFirst("\\d+_\\d+_", "");

            if (fragm.contains("-")) {
                final var s_p = fragm.split("-");
                nrPara = Integer.parseInt(s_p[0]);
                nrSentence = Integer.parseInt(s_p[1]);
            } else
                nrPara = Integer.parseInt(fragm);
        }

        final var path  = id.replaceAll("__", "/");
        try {
            final var txt = textbaseClient.getTextPlain(path);
            final var text = txt.split("\n");

            final var para = text[nrPara];
            if (nrSentence != null) {
                final var sentences = para.split("\\.");
                return sentences[nrSentence];
            } else
                return para;
        } catch (Exception e) {
            log.warn(e, e);
            return "";
        }
    }

    public static void main(String[] args) {
        final BibliotecaClient tbc = new BibliotecaClient("http://localhost:8080", new RestTemplate());
        final var cr = new TextbaseDl2TextbaseContentResolver(tbc);
        System.out.println(cr.resolve("cosbuc/nepublicate_in_volum/0073_41116_cosbuc__nepublicate_in_volum__petrea.txt#72-0"));
        System.out.println(cr.resolve("inexistant"));
        System.out.println(cr.resolve("poi"));
    }
}
