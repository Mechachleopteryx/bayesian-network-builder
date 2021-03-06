/*
 * SPDX-FileCopyrightText: 2020 SAP SE or an SAP affiliate company and bayesian-network-builder contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sap.bnb.graph

import com.sap.bnb.bn.{BE, CPT1, CPT2, Sure}
import com.sap.bnb.dsl.{From, To}

/**
  * @author Giancarlo Frison <giancarlo.frison@sap.com>
  */
object DSLGraph {
  def apply(dict: Map[String, Set[Any]], dictFuture: Map[String, Set[Any]]) = {
    val (nodes, priors) = GraphTransformer(dict)
    val (nodesFuture, _) = GraphTransformer(dictFuture)
    //map all variables types
    val cases: Map[String, Set[Any]] =
      dict.foldLeft(Map.empty[String, Set[Any]]) {
        case (acc, (node: String, rels: Set[Any])) =>
          acc ++ rels.map {
            case From((_, _, cpt: CPT2[Any, Any, Any])) =>
              (node, cpt.flatMap(_._2.chances.keySet).toSet)
            case From((_, cpt: CPT1[Any, Any])) =>
              (node, cpt.flatMap(_._2.chances.keySet).toSet)
            case To((cpt: CPT1[Any, Any], to: String)) => (node, cpt.keys.toSet)
            case To((cpt: CPT2[Any, Any, Any], a: String, b: String)) =>
              (node, cpt.values.flatMap(_.chances.keySet).toSet)
            case x: BE[Any] => (node, x.chances.keySet)
          }.toMap
      }
    new DSLGraph(nodes, nodesFuture, cases, () => priors)
  }
}

/**
  * @param nodes  graph's nodes
  * @param nodesTemporal nodes with temporal effects
  * @param cases  mapping of values of all random variables
  * @param priorsF prior values
  */
class DSLGraph(
    val nodes: Map[String, BNode],
    val nodesTemporal: Map[String, BNode],
    val cases: Map[String, Set[Any]],
    val priorsF: () => CPT1[String, Any]
) {

  class Solver[T](
      val entries: Map[String, BNode],
      val priors: CPT1[String, Any]
  ) {

    /**
      * returns calculated values of forward probability
      */
    val deduce: (String, CPT1[String, Any]) => CPT1[String, Any] =
      (name, session) =>
        session.get(name) match {
          case Some(_) => session
          case None =>
            entries(name).source
              .flatMap(s =>
                s(sub => session.get(sub).orElse(deduce(sub, session).get(sub)))
              )
              .map(newValue => session + ((name, newValue)))
              .getOrElse(session)
        }

    /**
      * returns only a list of calculated posteriors (inverse probability)
      */
    val induce
        : (String, CPT1[String, Any], Set[String]) => Map[String, BE[Any]] =
      (nodeName, session, memoization) =>
        if (memoization.contains(nodeName)) Map.empty
        else {
          entries(nodeName).posterior match {
            case Some(posterior) => {
              val children =
                posterior
                  .ends()
                  .flatMap(induce(_, session, memoization + nodeName))
                  .toMap
              posterior(end =>
                children
                  .get(end)
                  .orElse(deduce(end, session).get(end))
              )
                .map(v => children + (nodeName -> v))
                .getOrElse(children)
            }
            case None => Map.empty
          }
        }
    def apply(
        toSolve: String,
        evidences: CPT1[String, Any]
    ) = {
      // solve all elements in the network
      nodes.keys.foldLeft(priors ++ evidences)((acc, name) => {
        val forwardValues = deduce(name, acc)
        val posteriors = induce(name, forwardValues, Set.empty)
        forwardValues ++ posteriors
      })
    }
  }

  protected def solveIntern[T](
      toSolve: String,
      evidences: CPT1[String, Any]
  ): Iteration[T] = {
    val priors = priorsF()
    val solver = new Solver[T](nodes, priors)
    val post: Map[String, BE[Any]] =
      solver(toSolve, evidences)

    val futSolv =
      new Solver[T](
        nodes ++ nodesTemporal,
        Map.empty
      )
    val futuresF = () =>
      nodesTemporal
        .filter(_._2.posterior.isEmpty)
        .keys
        .map(futureName => {
          (futureName, futSolv(futureName, post - futureName)(futureName))
        })
        .toMap
    Iteration[T](
      post.get(toSolve).map(_.asInstanceOf[BE[T]]),
      new DSLGraph(
        nodes,
        nodesTemporal,
        cases,
        () => (priors ++ futuresF()) - toSolve
      )
    )
  }

  def solve[T](toSolve: String): Iteration[T] =
    solveIntern[T](toSolve, Map.empty)
  def evidences(evidences: (String, Any)*) = {
    new {
      val evs = evidences.map {
        case (name: String, x: BE[Any]) => (name, x)
        case (name: String, x: Any)     => (name, Sure(x, cases(name)))
      }.toMap

      def solve[T](toSolve: String): Iteration[T] = solveIntern[T](toSolve, evs)

    }
  }

  case class Iteration[T](value: Option[BE[T]], next: DSLGraph)

}
