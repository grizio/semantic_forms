package deductions.runtime.html

import deductions.runtime.abstract_syntax.FormModule
import deductions.runtime.utils._
//import org.w3.banana.RDFModule

import scala.util.Try
import scala.xml.{Elem, NodeSeq}

/** generate HTML from abstract Form : common parts for Display & edition */
private[html] trait Form2HTMLBase[NODE, URI <: NODE]
    extends BasicWidgets
    with CSSClasses
    with RDFPrefixesInterface
    {

  val config: Configuration
  import config._


  def toPlainString(n: NODE): String = n.toString()
  
  type formMod = FormModule[NODE, URI]
  type FormEntry = formMod#Entry

//  lazy val prefixes = new RDFPrefixes[ImplementationSettings.Rdf]
//		  with ImplementationSettings.RDFModule{}
  
  def makeFieldLabel(preceding: formMod#Entry, field: formMod#Entry, editable: Boolean)
  (implicit form: FormModule[NODE, URI]#FormSyntax) = {
    // display field label only if different from preceding
    if (preceding.label != field.label)
      // PENDING is it correct HTML5 ?
      <label for={
//      makeHTMLName(field)
      field.htmlName }
        class={ css.cssClasses.formLabelCSSClass }
        >
      <a href={field.property.toString()}
    title={
        labelTooltip(field)
      }
    target="_blank"
    style= {"""
/* Do not show labels like links */
    text-decoration: none;
    color: #000;
    font-weight: bold;
"""}
      draggable="true"
      data-uri-property={field.property.toString()}>{
        val label = field.label
        // hack before implementing real separators
        if (label.contains("----"))
          label.substring(1).replaceAll("-(-)+", "")
        else label
      }</a>
      </label>
    else if(editable){
      <label class={ css.cssClasses.formLabelCSSClass } title={
      field.comment + " - " + field.property
      }> -- </label>
        <div class={css.cssClasses.formAddDivCSSClass}></div>
    }
    else {
      <label class={ css.cssClasses.formLabelCSSClass } title={
      field.comment + " - " + field.property
      }> -- </label>
    }


  }

  private def labelTooltip(field: formMod#Entry) = {
    val details = if( displayTechnicalSemWebDetails )
        s"""
          property: ${field.property} -
          type: ${field.type_}"""
          else ""
      s"""${field.comment} - $details"""
  }
  
  def message(m: String,lang: String): String = I18NMessages.get(m, lang)

  def isFirstFieldForProperty( field: formMod#Entry )
    (implicit form: FormModule[NODE, URI]#FormSyntax): Boolean = {
    val ff = form.fields
    val previous = Try(ff(ff.indexOf(field) - 1)).toOption
    previous match {
      case Some(fi) => fi.property != field.property
      case None => true
    }
  }

//  /** leveraging on HTTP parameter being the original triple from TDB,
//   * in N-Triple syntax, we generate here the HTTP parameter from the original triple;
//   * see HttpParamsManager#httpParam2Triple for the reverse operation */
//  def makeHTMLName(ent: formMod#Entry)(implicit form: FormModule[NODE, URI]#FormSyntax): String = {
//    val rawResult = {
//      def makeTTLURI(s: NODE) = s"<$s>"
//      def makeTTLBN(s: NODE) = s"_:$s"
//      def makeTTLAnyTerm(value: NODE, ent: formMod#Entry) = {
//        ent match {
//          case lit: formMod#LiteralEntry => value
//          case bn: formMod#BlankNodeEntry => makeTTLBN(value)
//          case _ => makeTTLURI(value)
//        }
//      }
//      makeTTLURI(form.subject) + " " +
//        makeTTLURI(ent.property) + " " +
//        makeTTLAnyTerm(ent.value, ent) + " .\n"
//    }
//    println( s"""Form2HTMLBase.makeHTMLName Entry $ent ==> "$rawResult" """)
//    urlEncode(rawResult)
//  }

//  /** make HTML name for a triple */
//  def makeHTMLNameResource(re: formMod#Entry)(implicit form:
//      FormModule[NODE, URI]#FormSyntax) =
//    makeHTMLName(re)


//  def makeHTMLNameBN(re: formMod#Entry)(implicit form: FormModule[NODE, URI]#FormSyntax) = makeHTMLName(re)
//  def makeHTMLNameLiteral(lit: formMod#LiteralEntry)(implicit form: FormModule[NODE, URI]#FormSyntax) =
//    makeHTMLName(lit)

  def makeHTMLIdResourceSelect(re: formMod#Entry)(implicit form: FormModule[NODE, URI]#FormSyntax): String =
    toPlainString(re.property)

  def makeHTML_Id(entry: formMod#Entry)(implicit form: FormModule[NODE, URI]#FormSyntax) =
    "f" + form.fields.indexOf(entry)

  /** URL Encode the RDF node */
  def urlEncode(node: Any) = Form2HTML.urlEncode(node) // URLEncoder.encode(node.toString, "utf-8")

  def createHyperlinkString(hrefPrefix: String = config.hrefDisplayPrefix, uri: String, blanknode: Boolean = false): String = {
    if (hrefPrefix == "")
      uri
    else {
      val suffix = if (blanknode) "&blanknode=true" else ""
      hrefPrefix + urlEncode(uri) + suffix
    }
  }

  /** use this instead of createHyperlinkString() */
  def createHyperlinkElement(uri: String, text: String, hrefPrefix: String = config.hrefDisplayPrefix, blanknode: Boolean = false): NodeSeq = {
    <a  href={createHyperlinkString(hrefPrefix, uri, blanknode)} style="color: rgb(44,133,254);">
    {text}</a>
  }

  /** add data- HTML5 Attributes corresponding to triple To XML Element */
  def addTripleAttributesToXMLElement(elem: Elem, entry: FormEntry): Elem = {
    import entry._
    if( entry .property != null )
    addAttributesToXMLElement(elem, Map(
      "data-rdf-subject" -> subject.toString(),
      "data-rdf-property" -> property.toString(),
      "data-rdf-object" -> value.toString(),
      "data-rdf-type" -> type_.toString()))
      else elem
  }

  /** add Attributes To XML Element - Note could be reused */
  private def addAttributesToXMLElement(elem: Elem, config: Map[String, String]): Elem =
		  elem.copy(attributes = config.foldRight(elem.attributes) {
		  case ((k, v), next) => new scala.xml.UnprefixedAttribute(k, v, next)
		  })
}