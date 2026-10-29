import java.nio.file.*;
import java.io.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
public class PdfaFixtures {
 public static void main(String[] args) throws Exception {
  Path dir=Path.of(args[0]); Files.createDirectories(dir);
  for(String kind:new String[]{"Vector", "Embedded", "Unembedded"}) {
   try(var doc=new PDDocument()) {
    var page=new PDPage();doc.addPage(page);
    try(var cs=new PDPageContentStream(doc,page)) {
     cs.addRect(20,20,80,90);cs.fill();
     if(!kind.equals("Vector")) {
      PDFont font=kind.equals("Embedded")?PDType0Font.load(doc,Path.of(args[1]).toFile()):new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      cs.beginText();cs.setFont(font,14);cs.newLineAtOffset(40,160);cs.showText(kind.equals("Embedded")?"Swona café Ω":"Swona");cs.endText();
     }
    }
    doc.save(dir.resolve(kind+".pdf").toFile());
   }
  }
 }
}
