import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

class StructuredFixtures {
    public static void main(String[] args) throws Exception {
        var root=Path.of(args[0]);
        try(var doc=new PDDocument()) {
            for(int page=0;page<2;page++) {
                var sheet=new PDPage();doc.addPage(sheet);
                try(var stream=new PDPageContentStream(doc,sheet)) {
                    for(int row=0;row<=3;row++){stream.moveTo(50,700-row*30);stream.lineTo(450,700-row*30);}
                    for(int column=0;column<=2;column++){stream.moveTo(50+column*200,700);stream.lineTo(50+column*200,610);}
                    stream.stroke();
                    var values=page==0?new String[][]{{"Island","Value"},{"Swona","=SUM(A1:A2)"},{"Cliff","-42"}}:new String[][]{{"Name","Value"},{"Hill","@CMD"},{"Slate","+17"}};
                    for(int row=0;row<3;row++)for(int column=0;column<2;column++) {
                        stream.beginText();stream.setFont(new PDType1Font(FontName.HELVETICA),12);stream.newLineAtOffset(60+column*200,680-row*30);stream.showText(values[row][column]);stream.endText();
                    }
                }
            }
            doc.save(root.resolve("Tables with spaces.pdf").toFile());
        }
        try(var doc=new PDDocument()){doc.addPage(new PDPage());doc.save(root.resolve("No tables.pdf").toFile());}
    }
}
