import java.nio.file.*;
import java.io.*;
import java.awt.image.BufferedImage;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.form.*;

class JsonFixtures {
 static COSArray numbers(float... values){var a=new COSArray();for(float value:values)a.add(new COSFloat(value));return a;}
 public static void main(String[] args)throws Exception {
  try(var doc=new PDDocument()){
   var page=new PDPage(PDRectangle.LETTER);page.setRotation(90);doc.addPage(page);doc.getDocumentInformation().setTitle("Swona editable JSON fixture");
   var font=PDType0Font.load(doc,Path.of(args[1]).toFile());
   try(var content=new PDPageContentStream(doc,page)){
    content.beginText();content.setFont(font,18);content.newLineAtOffset(72,700);content.showText("Swona café Ω editable text");content.endText();
    var image=new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();graphics.setColor(java.awt.Color.GREEN);graphics.fillRect(0,0,20,20);graphics.dispose();content.drawImage(LosslessFactory.createFromImage(doc,image),72,500,80,80);
   }
   var note=new PDAnnotationText();note.setContents("Slate cliff annotation");note.setRectangle(new PDRectangle(200,500,20,20));page.getAnnotations().add(note);
   var form=new PDAcroForm(doc);doc.getDocumentCatalog().setAcroForm(form);var resources=new PDResources();resources.put(COSName.getPDFName("Helv"),new PDType1Font(Standard14Fonts.FontName.HELVETICA));form.setDefaultResources(resources);form.setDefaultAppearance("/Helv 12 Tf 0 g");
   var field=new PDTextField(form);field.setPartialName("islandName");var widget=field.getWidgets().getFirst();widget.setRectangle(new PDRectangle(72,600,180,25));widget.setPage(page);page.getAnnotations().add(widget);form.getFields().add(field);field.setValue("Swona form");
   var second=new PDPage();doc.addPage(second);var dictionary=new COSDictionary();dictionary.setItem(COSName.TYPE,COSName.FONT);dictionary.setItem(COSName.SUBTYPE,COSName.TYPE3);dictionary.setItem(COSName.FONT_BBOX,numbers(0,0,600,700));dictionary.setItem(COSName.FONT_MATRIX,numbers(.001f,0,0,.001f,0,0));dictionary.setInt(COSName.FIRST_CHAR,65);dictionary.setInt(COSName.LAST_CHAR,65);dictionary.setItem(COSName.WIDTHS,numbers(600));dictionary.setItem(COSName.RESOURCES,new COSDictionary());
   var proc=new PDStream(doc);try(var stream=proc.createOutputStream()){stream.write("600 0 d0 0 0 600 700 re f".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}var procs=new COSDictionary();procs.setItem(COSName.getPDFName("A"),proc);dictionary.setItem(COSName.CHAR_PROCS,procs);
   var encoding=new COSDictionary();var differences=new COSArray();differences.add(COSInteger.get(65));differences.add(COSName.getPDFName("A"));encoding.setItem(COSName.DIFFERENCES,differences);dictionary.setItem(COSName.ENCODING,encoding);
   var unicode=new PDStream(doc);try(var stream=unicode.createOutputStream()){stream.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def /CMapName /Fixture def /CMapType 2 def 1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfchar <41> <0041> endbfchar endcmap CMapName currentdict /CMap defineresource pop end end").getBytes(java.nio.charset.StandardCharsets.US_ASCII));}dictionary.setItem(COSName.TO_UNICODE,unicode);
   second.setResources(new PDResources());second.getResources().put(COSName.getPDFName("F3"),new PDType3Font(dictionary));var stream=new PDStream(doc);try(var output=stream.createOutputStream()){output.write("BT /F3 30 Tf 72 700 Td <41> Tj ET".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}second.setContents(stream);
   doc.save(Path.of(args[0]).resolve("Editable with spaces.pdf").toFile());
  }
 }
}
