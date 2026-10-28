import java.nio.file.*;
import java.awt.geom.Rectangle2D;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hslf.usermodel.*;

/** Synthetic editable Office documents and a ruled PDF table for independent worker goldens. */
public class OfficeFixtures {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);Files.createDirectories(root);String text="Swona café Ω editable Office";
  try(var doc=new XWPFDocument();var out=Files.newOutputStream(root.resolve("Word with spaces.docx"))){doc.createParagraph().createRun().setText(text);doc.write(out);}
  try(var deck=new XMLSlideShow();var out=Files.newOutputStream(root.resolve("Slides.pptx"))){var slide=deck.createSlide();var box=slide.createTextBox();box.setAnchor(new Rectangle2D.Double(50,50,600,100));box.setText(text);deck.write(out);}
  try(var deck=new HSLFSlideShow();var out=Files.newOutputStream(root.resolve("Legacy.ppt"))){var slide=deck.createSlide();var box=new HSLFTextBox();box.setAnchor(new Rectangle2D.Double(50,50,600,100));box.setText(text);slide.addShape(box);deck.write(out);}
  try(var workbook=new XSSFWorkbook();var out=Files.newOutputStream(root.resolve("Workbook.xlsx"))){var sheet=workbook.createSheet("Swona");sheet.createRow(0).createCell(0).setCellValue(text);sheet.createRow(1).createCell(0).setCellValue(42);workbook.write(out);}
  Files.writeString(root.resolve("Notes.txt"),text);Files.writeString(root.resolve("Table.csv"),"Place,Count\nSwona,42\nHill,7");Files.writeString(root.resolve("Table.tsv"),"Place\tCount\nSwona\t42");
  try(var pdf=new PDDocument()){var page=new PDPage();pdf.addPage(page);var font=PDType0Font.load(pdf,Path.of(args[1]).toFile());try(var content=new PDPageContentStream(pdf,page)){content.beginText();content.setFont(font,16);content.newLineAtOffset(60,740);content.showText(text);content.endText();String[][] cells={{"Place","Count","Note"},{"Swona","42","Hill"},{"Slate","7","Cliff"}};for(int r=0;r<3;r++)for(int c=0;c<3;c++){content.addRect(60+c*130,600-r*35,130,35);content.stroke();content.beginText();content.setFont(font,12);content.newLineAtOffset(70+c*130,612-r*35);content.showText(cells[r][c]);content.endText();}}pdf.save(root.resolve("Office source.pdf").toFile());}
 }
}
