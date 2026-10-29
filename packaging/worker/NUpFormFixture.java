import java.nio.file.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.apache.pdfbox.cos.COSName;
public class NUpFormFixture {
 public static void main(String[] args) throws Exception {
  try(var doc=new PDDocument()) {
   for(int i=0;i<2;i++) doc.addPage(new PDPage(new PDRectangle(20,30,200,300)));
   var form=new PDAcroForm(doc);doc.getDocumentCatalog().setAcroForm(form);
   var resources=new PDResources();resources.put(COSName.getPDFName("Helv"),new PDType1Font(Standard14Fonts.FontName.HELVETICA));form.setDefaultResources(resources);form.setDefaultAppearance("/Helv 12 Tf 0 g");
   var field=new PDTextField(form);field.setPartialName("SwonaEntry");field.setDefaultAppearance("/Helv 12 Tf 0 g");field.setReadOnly(true);form.getFields().add(field);
   var widget=field.getWidgets().get(0);widget.setRectangle(new PDRectangle(50,100,80,20));widget.setPage(doc.getPage(0));widget.setPrinted(true);doc.getPage(0).getAnnotations().add(widget);field.setValue("Swona");
   doc.save(Path.of(args[0]).toFile());
  }
 }
}
