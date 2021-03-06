package controllers

import java.net.URLEncoder

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.Elem
import scala.xml.NodeSeq

import deductions.runtime.html.TableView
import deductions.runtime.jena.ImplementationSettings
import deductions.runtime.services.html.Form2HTMLBanana
import deductions.runtime.services.html.HTML5TypesTrait
import deductions.runtime.utils.Configuration
import deductions.runtime.utils.DefaultConfiguration
import deductions.runtime.core.HTTPrequest
import deductions.runtime.utils.RDFPrefixes
import deductions.runtime.views.ToolsPage
import play.api.mvc.Action
import play.api.mvc.Controller
import play.api.mvc.Request
import deductions.runtime.utils.RDFStoreLocalProvider
import deductions.runtime.core.SemanticController
import play.api.mvc.Result
import play.api.mvc.EssentialAction

import scalaz._
import Scalaz._
import deductions.runtime.views.FormHeader

/** controller for HTML pages ("generic application") */
trait WebPages extends Controller with ApplicationTrait
  with FormHeader[ImplementationSettings.Rdf, ImplementationSettings.DATASET] {
  import config._

  def index(): EssentialAction = {
    recoverFromOutOfMemoryErrorGeneric(
      {
        val contentMaker = new SemanticController {
          def result(request: HTTPrequest): NodeSeq =
            makeHistoryUserActions("15", request)
        }
        outputMainPageWithContent(contentMaker)
      },
      (t: Throwable) =>
        errorActionFromThrowable(t, "in landing page /index"))
  }

  /** no call of All Service Listeners */
  private case class MainPagePrecomputePlay(
      request: Request[_] ) {
    val requestCopy: HTTPrequest = copyRequest(request)
    // TODO copied below in MainPagePrecompute
    val lang = requestCopy.getLanguage()
    val userid = requestCopy.userId()
    val uri = expandOrUnchanged( requestCopy.getRDFsubject() )
    val title = labelForURITransaction(uri, lang)
    val userInfo = displayUser(userid, requestCopy.getRDFsubject(), title, lang)
  }

  /** side effet: call of All Service Listeners */
  private case class MainPagePrecompute(
      val requestCopy: HTTPrequest) {
    callAllServiceListeners(requestCopy)
    val lang = requestCopy.getLanguage()
    val userid = requestCopy.userId()
    val uri = expandOrUnchanged( requestCopy.getRDFsubject() )
    val title = labelForURITransaction(uri, lang)
    val userInfo = displayUser(userid, requestCopy.getRDFsubject(), title, lang)
    def this(request: Request[_]) = this(copyRequest(request))
  }

  /** output Main Page With given Content */
  private def outputMainPageWithContent(contentMaker: SemanticController) = {
    Action { request0: Request[_] =>
        val precomputed = new MainPagePrecompute(request0)
        import precomputed._
        addAppMessageFromSession(requestCopy)
        outputMainPage2(contentMaker.result(requestCopy),
            precomputed )
    }
  }

  /** same as before, but Logging is enforced */
  private def outputMainPageWithContentLogged(contentMaker: SemanticController) = {
    withUser {
      implicit userid =>
        implicit request =>
          val precomputed = new MainPagePrecompute(request)
          import precomputed._
          addAppMessageFromSession(requestCopy)
          println(s"========= outputMainPageWithContentLogged precomputed $precomputed - title ${precomputed.title}")
          outputMainPage2(contentMaker.result(requestCopy),
            precomputed )
    }
  }

  def addAppMessageFromSession(requestCopy: HTTPrequest) = {
//    println( ">>>> addAppMessageFromSession session " + requestCopy.session )
//    println( ">>>> addAppMessageFromSession cookies " + requestCopy.cookies )
    val stringMess = requestCopy.flashCookie( "message" )
//    println( ">>>> addAppMessageFromSession stringMess " + stringMess )
    requestCopy.addAppMessage(
      <p>{ stringMess }</p>)
  }

  class ResultEnhanced(result: Result) {
    /** common HTTP headers for HTML */
    def addHttpHeaders(): Result = {
      result
        .withHeaders("Access-Control-Allow-Origin" -> "*") // for dbpedia lookup
        .as("text/html; charset=utf-8")
    }
    /** HTTP header Link rel='alternate' for declaring other RDF formats */
    def addHttpHeadersLinks(precomputed: MainPagePrecompute): Result = {
      addHttpHeadersLinks(precomputed.requestCopy.uri)
    }
    /** HTTP header Link rel='alternate' for declaring other RDF formats */
    def addHttpHeadersLinks(uri: String): Result = {
      val seq =
        for (
          (syntax, mime) <- Seq(
            ("Turtle", "application/turtle"),
            ("RDF/XML", "application/rdf+xml"),
            ("JSON-LD", "application/ld+json"))
        ) yield downloadURI(uri, syntax) + s"; rel='alternate'; type='$mime' , "
      result.withHeaders("Link" -> seq.mkString(""))
    }
  }
  import scala.language.implicitConversions
  implicit def resultToResult(r: Result) = new ResultEnhanced(r)

  /** generate a Main Page wrapping given XHTML content */
  private def outputMainPage( content: NodeSeq,
      lang: String, userInfo: NodeSeq = <div/>, title: String = "",
      displaySearch:Boolean = true,
      classForContent: String = "container sf-complete-form")
  (implicit request: Request[_]) = {
      Ok( "<!DOCTYPE html>\n" +
        mainPage( content,
            userInfo, lang, title,
            displaySearch,
            messages = getDefaultAppMessage(),
            headExtra = getDefaultHeadExtra(),
            classForContent,
            copyRequest(request) )
      )
      .addHttpHeaders()
      .addHttpHeadersLinks( request.uri )
  }

  /** generate a Main Page wrapping given XHTML content */
  private def outputMainPage2(
    content:         NodeSeq,
    precomputed:     MainPagePrecompute,
    displaySearch:   Boolean            = true,
    classForContent: String             = "container sf-complete-form") = {
    import precomputed._
    Ok("<!DOCTYPE html>\n" +
      mainPage(
        content,
        userInfo, lang, title,
        displaySearch,
        messages = getDefaultAppMessage(),
        headExtra = getDefaultHeadExtra(),
        classForContent,
        requestCopy
    ))
      .addHttpHeaders()
      .addHttpHeadersLinks( precomputed )
  }

  /**
   * (re)load & display URI
   *
   *  NOTE: parameters are just used by Play! to check presence of parameters,
   *  the HTTP request is analysed by case class MainPagePrecompute
   *
   *  @param Edit edit mode <==> param not ""
   */
  def displayURI(uri0: String, blanknode: String = "", Edit: String = "",
                 formuri: String = "") : EssentialAction
  = {
    recoverFromOutOfMemoryErrorGeneric(
      {
        val contentMaker = new SemanticController {
          def result(request: HTTPrequest): NodeSeq = {
            val precomputed: MainPagePrecompute = MainPagePrecompute(request)
            import precomputed._
            logger.info(s"displayURI: expandOrUnchanged $uri")
            val userInfo = displayUser(userid, uri, title, lang)
            htmlForm(uri, blanknode, editable = Edit  =/=  "", lang, formuri,
              graphURI = makeAbsoluteURIForSaving(userid),
              request = request)._1
          }
        }

        if (needLoginForDisplaying || (needLoginForEditing && Edit  =/=  ""))
          outputMainPageWithContentLogged(contentMaker)
        else
          outputMainPageWithContent(contentMaker)
      },
      (t: Throwable) =>
        errorActionFromThrowable(t, "in /display URI"))
  }

  def table = Action { implicit request: Request[_] =>
    val requestCopy = getRequestCopy()
    val query = queryFromRequest(requestCopy)
    recoverFromOutOfMemoryErrorGeneric(
      {
        val userid = requestCopy.userId()
        val title = "Table view from SPARQL"
        val lang = chooseLanguage(request)
        val userInfo = displayUser(userid, "", title, lang)
        val editButton = <button action="/table" title="Edit each cell of the table (like a spreadheet)">Edit</button>
        outputMainPage(
            <div>
              <a href={ "/sparql-ui?query=" + URLEncoder.encode(query, "UTF-8") }>Back to SPARQL page</a>
            </div> ++
            <form> {
              editButton ++
              <input name="query" type="hidden" value={query}></input> ++
              <input name="edit" type="hidden" value={
                // request.queryString.getOrElse("edit", Seq()).headOption.getOrElse("yes")
                "yes"
              }></input> ++
              tableFromSPARQL(requestCopy)
            } </form>,
          lang, title = title,
          userInfo = userInfo,
          classForContent = "")
      },
      (t: Throwable) =>
        errorResultFromThrowable(t, s"in make table /table?query=$query"))
  }


  private def tableFromSPARQL(request: HTTPrequest): NodeSeq = {
    /** TODO also elsewhere */
    def isEditableFromRequest(request: HTTPrequest): Boolean =
      request.queryString.getOrElse("edit", Seq()).headOption.getOrElse("") != ""

    val query = queryFromRequest(request)
    val formSyntax = createFormFromSPARQL(query,
      editable = isEditableFromRequest(request),
      formuri = "", request)
    val tv = new TableView[ImplementationSettings.Rdf#Node, ImplementationSettings.Rdf#URI]
        with Form2HTMLBanana[ImplementationSettings.Rdf]
        with ImplementationSettings.RDFModule
        with HTML5TypesTrait[ImplementationSettings.Rdf]
        with RDFPrefixes[ImplementationSettings.Rdf]{
      val config = new DefaultConfiguration {}
      val nullURI = ops.URI("")
    }
    tv.generate(formSyntax, request: HTTPrequest)
  }

  private def queryFromRequest(request: HTTPrequest): String =
    request.queryString.getOrElse("query", Seq()).headOption.getOrElse("")

  /** "naked" HTML form */
  def form(uri: String, blankNode: String = "", Edit: String = "", formuri: String = "",
           database: String = "TDB") =
    Action {
      implicit request: Request[_] =>
        recoverFromOutOfMemoryErrorGeneric(
          {
            logger.info(s"""form: request $request : "$Edit" formuri <$formuri> """)
            val lang = chooseLanguage(request)
            val requestCopy = getRequestCopy()
            val userid = requestCopy.userId()
            Ok(htmlForm(uri, blankNode, editable = Edit  =/=  "", lang, formuri,
              graphURI = makeAbsoluteURIForSaving(userid), database = database, HTTPrequest() )._1)
              .withHeaders(ACCESS_CONTROL_ALLOW_ORIGIN -> "*")
              .withHeaders(ACCESS_CONTROL_ALLOW_HEADERS -> "*")
              .withHeaders(ACCESS_CONTROL_ALLOW_METHODS -> "*")
              .as("text/html; charset=utf-8")
          },
          (t: Throwable) =>
            errorResultFromThrowable(t, s"in /form?uri=$uri"))
    }

  /**
   * /sparql-form service: Create HTML form or view from SPARQL (construct);
   *  like /sparql has input a SPARQL query;
   *  like /form and /display has parameters Edit, formuri & database
   */
  def sparqlForm(query: String, Edit: String = "", formuri: String = "",
                 database: String = "TDB") =
    Action { implicit request: Request[_] =>
      recoverFromOutOfMemoryErrorGeneric(
        {
          val requestCopy = getRequestCopy()
          val userid = requestCopy.userId()
          val lang = chooseLanguage(request)
          val userInfo = displayUser(userid, "", "", lang)
          outputMainPage(
            createHTMLFormFromSPARQL(
              query,
              editable = Edit  =/=  "",
              formuri, requestCopy),
            lang, userInfo)
        },
        (t: Throwable) =>
          errorResultFromThrowable(t, "in /sparql-form"))
    }

  /** SPARQL Construct UI */
  def sparql(query: String) : EssentialAction = {
    logger.info("sparql: " + query)

    def doAction(implicit request: Request[_]) = {
      logger.info("sparql: " + request)
      val httpRequest = copyRequest(request)
      val lang = httpRequest.getLanguage()
      outputMainPage(
          sparqlConstructQueryHTML(query, lang, httpRequest, context=httpRequest.queryString2),
          lang)
        // TODO factorize
        .withHeaders(ACCESS_CONTROL_ALLOW_ORIGIN -> "*")
        .withHeaders(ACCESS_CONTROL_ALLOW_HEADERS -> "*")
        .withHeaders(ACCESS_CONTROL_ALLOW_METHODS -> "*")
    }

    recoverFromOutOfMemoryErrorGeneric(
      {
        if (needLoginForDisplaying)
          Action { implicit request: Request[_] => doAction }
        else
          withUser { implicit userid => implicit request => doAction }
      },
      (t: Throwable) =>
        errorActionFromThrowable(t, "in SPARQL Construct UI /sparql-ui"))
  }

  /** SPARQL select UI */
  def select(query: String) =
    Action {
    implicit request: Request[_] =>
      recoverFromOutOfMemoryErrorGeneric(
        {
            logger.info("sparql: " + request)
            logger.info("sparql: " + query)
            val lang = chooseLanguage(request)
            outputMainPage(
              selectSPARQL(query, lang, copyRequest(request)), lang )
        },
        (t: Throwable) =>
          errorResultFromThrowable(t, "in SPARQL UI /select-ui"))
    }


  /** search Or load+Display Action */
  def searchOrDisplayAction(q: String) = {
    def isURI(q: String): Boolean =
      // isAbsoluteURI(q)
      q.contains(":")
    
    if (isURI(q))
      displayURI( q, Edit="" )
    else
      wordsearchAction(q)
  }

  def wordsearchAction(q: String = "", clas: String = "") = Action.async {
    implicit request: Request[_] =>
      recoverFromOutOfMemoryErrorGeneric(
        {
          val httpRequest = copyRequest(request)
          val classe =
            clas match {
              case classe if (classe =/= "") => classe
              case _                         => httpRequest.getHTTPparameterValue("clas").getOrElse("")
            }
          val fut = wordsearchFuture(q, classe, httpRequest)
          fut.map(r => outputMainPage(r, httpRequest.getLanguage()))
        },
        (t: Throwable) =>
          Future{ errorResultFromThrowable(t, "in word search /wordsearch") }
          )
  }

  /** show Named Graphs - pasted from above wordsearchAction */
  def showNamedGraphsAction() = Action.async {
    implicit request: Request[_] =>
    val httpRequest = copyRequest(request).
      setDefaultHTTPparameterValue("limit", "200").
      setDefaultHTTPparameterValue("offset", "1")
    val fut = recoverFromOutOfMemoryError( showNamedGraphs(httpRequest) )
    val lang = httpRequest.getLanguage()
    val rr = fut.map( r => outputMainPage( r, lang ) )
    rr
  }

  /** show Triples In given Graph */
  def showTriplesInGraphAction(uri: String) = {
    Action.async { implicit request: Request[_] =>
      val lang = chooseLanguageObject(request).language
      val fut = recoverFromOutOfMemoryError(
        Future.successful(showTriplesInGraph(uri, lang)),
        s"in show Triples In Graph /showTriplesInGraph?uri=$uri")
      val rr = fut.map(r => outputMainPage(r, lang))
      rr
    }
  }

  /////////////////////////////////

  def edit(uri: String): EssentialAction =
    withUser { implicit userid => implicit request =>
      recoverFromOutOfMemoryErrorGeneric(
        {
          val lang = chooseLanguageObject(request).language
          val pageURI = uri
          val pageLabel = labelForURITransaction(uri, lang)
          val userInfo = displayUser(userid, pageURI, pageLabel, lang)
          logger.info(s"userInfo $userInfo, userid $userid")
          val content = htmlForm(
            uri, editable = true,
            lang = chooseLanguage(request), graphURI = makeAbsoluteURIForSaving(userid),
            request = copyRequest(request))._1
          Ok("<!DOCTYPE html>\n" +
             mainPage(content, userInfo, lang))
             .addHttpHeaders()
        },
        (t: Throwable) =>
          errorResultFromThrowable(t, "in /edit"))
    }

  /**
   * save the HTML form;
   *  intranet mode (needLoginForEditing == false): no cookies session, just receive a `graph` HTTP param.
   *  TODO: this pattern should be followed for each page or service
   */
  def saveAction(): EssentialAction = {

    def saveLocal(userid: String)(implicit request: Request[_]) = {
      val httpRequest = copyRequest(request)
      logger.debug(s"""ApplicationTrait.saveOnly: class ${request.body.getClass},
              request $httpRequest""")
      val (uri, typeChanges) = saveOnly(
        httpRequest, userid, graphURI = makeAbsoluteURIForSaving(userid))
      logger.info(s"saveAction: uri <$uri>, typeChanges=$typeChanges")
      val saveAfterCreate = httpRequest.getHTTPheaderValue("Referer") .filter( _ .contains("/create?") ).isDefined
      val edit = typeChanges && ! saveAfterCreate
      val editParam = if( edit ) "edit" else ""
      val call = routes.Application.displayURI(
        uri, Edit = editParam)
      Redirect(call).flashing(
        "message" ->
        s"The item <$uri> has been created" )
        // s"The item <$uri> of type <${httpRequest.getHTTPparameterValue("clas")}> has been created" )
      /* TODO */
      // recordForHistory( userid, request.remoteAddress, request.host )
    } // end saveLocal(

    recoverFromOutOfMemoryErrorGeneric(
      {
        if (needLoginForEditing)
          withUser { implicit userid => implicit request =>
            saveLocal(userid)
          }
        else
          Action { implicit request: Request[_] =>
            {
              val user = request.headers.toMap.getOrElse("graph", Seq("anonymous")).headOption.getOrElse("anonymous")
              saveLocal(user)
            }
          }
      },
      (t: Throwable) =>
        errorActionFromThrowable(t, "in save Actions /save"))
  }

  /** creation form - generic SF application */
  def createAction() =
    withUser { implicit userid => implicit request =>
      recoverFromOutOfMemoryErrorGeneric(
        {
          logger.info(s"create: request $request")
          // URI of RDF class from which to create instance
          val uri0 = getFirstNonEmptyInMap(request.queryString, "uri")
          val uri = expandOrUnchanged(uri0)
          // URI of form Specification
          val formSpecURI = getFirstNonEmptyInMap(request.queryString, "formuri")
          logger.info(s"""create: "$uri" """)
          logger.info(s"formSpecURI from HTTP request: <$formSpecURI>")
          val lang = chooseLanguage(request)
          val userInfo = displayUser(userid, uri, s"Create a $uri", lang)
          outputMainPage(
            create(uri, chooseLanguage(request),
              formSpecURI, makeAbsoluteURIForSaving(userid), copyRequest(request)).getOrElse(<div/>),
            lang, userInfo = userInfo)
        },
        (t: Throwable) =>
          errorResultFromThrowable(t, "in create Actions /create"))
    }

  def backlinksAction(uri: String = "") = Action.async {
    implicit request: Request[_] =>
      val requestCopy = copyRequest(request)
      val fut: Future[NodeSeq] =
        recoverFromOutOfMemoryError(
          backlinksFuture(uri, requestCopy) )

      val extendedSearchLink =
        <p>
          <a href={ "/esearch?q=" + URLEncoder.encode(uri, "utf-8") }>
            Extended Search for &lt;{ uri }
            &gt;
          </a>
        </p>

      val prec = MainPagePrecompute(requestCopy)
      val userInfo = prec.userInfo
      val lang = prec.lang

      fut.map { formattedResults =>
        outputMainPage(
          extendedSearchLink ++ formattedResults,
          lang, userInfo)
      }
  }

  def extSearch(q: String = "") = Action.async {
	  implicit request: Request[_] =>
	  val lang = chooseLanguage(request)
    val fut = recoverFromOutOfMemoryError(esearchFuture(q))
    fut.map(r =>
    outputMainPage(r, lang))
  }

  //  implicit val myCustomCharset = Codec.javaSupported("utf-8") // does not seem to work :(

  def toolsPage = {
    withUser {
      implicit userid =>
        implicit request =>
          val lang = chooseLanguageObject(request).language
          val config1 = config
          val userInfo = displayUser(userid, "", "", lang)
          outputMainPage(
            new ToolsPage with ImplementationSettings.RDFModule with RDFPrefixes[ImplementationSettings.Rdf] {
              override val config: Configuration = config1
            }.getPage(lang, copyRequest(request)), lang, displaySearch = false, userInfo = userInfo)
            .as("text/html; charset=utf-8")

    }
  }

  def makeHistoryUserActionsAction(limit: String): EssentialAction = {
    recoverFromOutOfMemoryErrorGeneric(
      {
      val contentMaker: SemanticController = new SemanticController {
        def result(request: HTTPrequest): NodeSeq = {
          val precomputed: MainPagePrecompute = MainPagePrecompute(request)
          import precomputed._
          logger.info(s"makeHistoryUserActionsAction:  $limit")
          logger.info("makeHistoryUserActionsAction: cookies: " + request.cookies.mkString("; "))
          makeHistoryUserActions(limit, request)
        }
      }
      outputMainPageWithContent(contentMaker)
    },
      (t: Throwable) =>
        errorActionFromThrowable(t, s"in make History of User Actions /history?limit=$limit"))
  }

}
