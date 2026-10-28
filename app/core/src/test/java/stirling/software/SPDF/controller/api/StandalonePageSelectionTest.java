package stirling.software.SPDF.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import stirling.software.common.util.GeneralUtils;
import stirling.software.pdf.PdfPageSelection;

class StandalonePageSelectionTest {
    @Test
    void retainedGrammarMatchesCommonParser() {
        for (String input :
                new String[] {
                    "all", "1,3-5", "2-", "2n", "n+1", "(n+1)*2", "n/2", "1,1,3", "nonsense", "9"
                }) {
            assertEquals(
                    GeneralUtils.parsePageList(input.split(","), 8, false),
                    PdfPageSelection.parsePageList(input.split(","), 8, false),
                    input);
        }
    }
}
