package deductions.runtime.rdf_links_rank

import scala.util.Try

import org.w3.banana.RDF
import org.w3.banana.RDFOps
import org.w3.banana.RDFStore
import org.w3.banana.syntax.RDFSyntax
import org.w3.banana.binder.FromLiteral
import org.w3.banana.PointedGraph
import org.w3.banana.binder.ToLiteral
import org.w3.banana.binder.FromLiteral
import org.w3.banana.SparqlOps

import collection._
import scala.util.Success
import scala.util.Failure

import java.rmi.UnexpectedException

import deductions.runtime.utils.RDFPrefixes
import deductions.runtime.utils.DatabaseChanges

trait RDFLinksCounter[Rdf <: RDF, DATASET]
    extends RDFPrefixes[Rdf] {
  self: RDFSyntax[Rdf] =>

  val linksCountPred = form("linksCount")
  implicit val ops: RDFOps[Rdf]
  implicit val rdfStore: RDFStore[Rdf, Try, DATASET]
  import ops._
  implicit val sparqlOps: SparqlOps[Rdf]
  import sparqlOps._
  import ToLiteral._
  import FromLiteral._

  /** update RDF Links Count, typically after user edits */
  def updateLinksCount(
    databaseChanges: DatabaseChanges[Rdf],
    linksCountDataset: DATASET,
    linksCountGraphURI: Rdf#URI) = {

    val countsSubjectsToAddSet = mutable.Set[Rdf#Node]()
    val countsSubjectsToRemoveSet = mutable.Set[Rdf#Node]()
    val countsMap = mutable.Map[Rdf#Node, Int]()
    val countsToRemoveMap = mutable.Map[Rdf#Node, Int]()

    /* count Changes */
    def countChanges(triplesChanged: Seq[Rdf#Triple],
                     countsSubjectsSet: mutable.Set[Rdf#Node],
                     countsMap: mutable.Map[Rdf#Node, Int]) =
      for (
        linksCountGraph <- rdfStore.getGraph(linksCountDataset, linksCountGraphURI);
        tripleToAdd <- triplesChanged;
        subject = tripleToAdd.subject if (tripleToAdd.objectt.isURI)
      ) {
        countsSubjectsSet.add(subject)
        countsMap.put(subject, countsMap.getOrElse(subject, 0) + 1)
      }

    countChanges(databaseChanges.triplesToAdd, countsSubjectsToAddSet, countsMap)
    countChanges(databaseChanges.triplesToRemove, countsSubjectsToRemoveSet, countsToRemoveMap)

    for (
      subject <- (countsSubjectsToAddSet ++ countsSubjectsToRemoveSet);
      linksCountGraph <- rdfStore.getGraph(linksCountDataset, linksCountGraphURI);
      oldCount <- getCountFromTDBTry(linksCountGraph, subject)
    ) {
      val count = oldCount
      +countsMap.getOrElse(subject, 0)
      -countsToRemoveMap.getOrElse(subject, 0)
      if (count != oldCount) {
        rdfStore.removeTriples(linksCountDataset, linksCountGraphURI,
          Seq(Triple(subject,
            linksCountPred,
            IntToLiteral(ops).toLiteral(oldCount))))
        rdfStore.appendToGraph(linksCountDataset, linksCountGraphURI,
          makeGraph(Seq(Triple(
            subject,
            linksCountPred,
            IntToLiteral(ops).toLiteral(count)))))
      }
    }
  }

  private def getCountFromTDBTry(linksCountGraph: Rdf#Graph,
                                 subject: Rdf#Node): Try[Int] = {
    val pg = PointedGraph(subject, linksCountGraph)
    (pg / linksCountPred).as[Int]
  }

  /** compute RDF Links Count from scratch, typically called in batch */
  def computeLinksCount(
    dataset: DATASET,
    linksCountDataset: DATASET,
    linksCountGraphURI: Rdf#URI) = {

    val query = """
      |SELECT DISTINCT ?S ( COUNT(?O) AS ?COUNT)
      |WHERE {
      |  GRAPH ?GR {
      |    ?S ?P ?O .
      |        FILTER ( isURI(?O) )
      |  }
      |}
      |GROUP BY ?S
      |ORDER BY DESC(?COUNT)""".stripMargin

    val solutionsTry = for {
      query <- parseSelect(query)
      solutions <- rdfStore.executeSelect(dataset, query, immutable.Map())
    } yield solutions

    val subjectCountIterator = solutionsTry match {
      case Success(solutions) =>
        val counts = for (
          solution <- solutions.toIterable;
          //          v = solution.varnames ;
          nodeIntPariTry = for (
            // cf SparqlSolutionSyntaxW
            s <- solution("?S");
            countNode <- solution("?COUNT");
            count <- foldNode(countNode)(
              _ => Failure(new UnexpectedException("computeLinksCount")),
              _ => Failure(new UnexpectedException("computeLinksCount")),
              literal => IntFromLiteral.fromLiteral(literal))
          ) yield { (s, count) } if (nodeIntPariTry.isSuccess)
        ) yield { nodeIntPariTry.toOption.get }
        counts
      case Failure(f) =>
        System.err.println(f)
        Seq((URI(""), 0)).toIterator
    }

    /* TODO would like to avoid both:
     * - creating the graph in memory :(
     * - calling appendToGraph for each triple
    */
    val tripleIterator = subjectCountIterator.map {
      case (s, count) =>
        Triple(
          s,
          linksCountPred,
          IntToLiteral(ops).toLiteral(count))
    }
    rdfStore.appendToGraph(linksCountDataset, linksCountGraphURI,
      makeGraph(tripleIterator.toIterable))
  }

}