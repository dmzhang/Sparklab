package com.cloudera.spark.graph.kshell

import org.apache.spark._
import org.apache.spark.graphx._

import scala.math._
import scala.reflect.ClassTag

object KCoreFast extends Logging {
  /**
   * Compute the k-core decomposition of the graph for all k <= kmax. This
   * uses the iterative pruning algorithm discussed by Alvarez-Hamelin et al.
   * in K-Core Decomposition: a Tool For the Visualization of Large Scale Networks
   * (see <a href="http://arxiv.org/abs/cs/0504107">http://arxiv.org/abs/cs/0504107</a>).
   *
   * @tparam VD the vertex attribute type (discarded in the computation)
   * @tparam ED the edge attribute type (preserved in the computation)
   *
   * @param graph the graph for which to compute the connected components
   * @param kmax the maximum value of k to decompose the graph
   *
   * @return a graph where the vertex attribute is the minimum of
   *         kmax or the highest value k for which that vertex was a member of
   *         the k-core.
   *
   * @note This method has the advantage of returning not just a single kcore of the
   *       graph but will yield all the cores for all k in [1, kmax].
   */

  def run[VD: ClassTag, ED: ClassTag](
                                       graph: Graph[VD, ED],
                                       kmax: Int,
                                       kmin: Int = 0,
                                       top: Int = -1)
  : (Int, Graph[Int, ED], Graph[Int, ED]) = {

    // Graph[(Int, Boolean), ED] - boolean indicates whether it is active or not
    var g = graph.outerJoinVertices(graph.degrees)((vid, oldData, newData) => newData.getOrElse(0)).cache
    var oldG = g
    // val degrees = graph.degrees
    // val numVertices = degrees.count
    // logWarning(s"Numvertices: $numVertices")
    // logWarning(s"degree sample: ${degrees.take(10).mkString(", ")}")
    // logWarning("degree distribution: " + degrees.map{ case (vid,data) => (data, 1)}.reduceByKey((_+_)).collect().mkString(", "))
    // logWarning("degree distribution: " + degrees.map{ case (vid,data) => (data, 1)}.reduceByKey((_+_)).take(10).mkString(", "))
    var curK = kmin - 1
    var vCount = Long.MaxValue
    var eCount = Long.MaxValue
    while (curK < kmax && vCount > top) {
      oldG.unpersist()
      oldG = g
      g = computeCurrentKCore(g, curK).cache
      vCount = g.vertices.filter { case (vid, vd) => vd >= curK }.count()
      eCount = g.triplets.filter { t => t.srcAttr >= curK && t.dstAttr >= curK }.count()
      logWarning(s"K=$curK, V=$vCount, E=$eCount")
      curK += 1
    }
    (curK, g.mapVertices({ case (_, k) => k }), oldG.mapVertices({ case (_, k) => k }))
  }

  def computeCurrentKCore[ED: ClassTag](graph: Graph[Int, ED], k: Int) = {
    logWarning(s"Computing kcore for k=$k")
    def sendMsg(et: EdgeTriplet[Int, ED]): Iterator[(VertexId, Int)] = {
      if (et.srcAttr < 0 || et.dstAttr < 0) {
        // if either vertex has already been turned off we do nothing
        Iterator.empty
      } else if (et.srcAttr < k && et.dstAttr < k) {
        // tell both vertices to turn off but don't need change count value
        Iterator((et.srcId, -1), (et.dstId, -1))

      } else if (et.srcAttr < k) {
        // if src is being pruned, tell dst to subtract from vertex count
        Iterator((et.srcId, -1), (et.dstId, 1))

      } else if (et.dstAttr < k) {
        // if dst is being pruned, tell src to subtract from vertex count
        Iterator((et.dstId, -1), (et.srcId, 1))

      } else {
        Iterator.empty
      }
    }

    // subtracts removed neighbors from neighbor count and tells vertex whether it was turned off or not
    def mergeMsg(m1: Int, m2: Int): Int = {
      if (m1 < 0 || m2 < 0) {
        -1
      } else {
        m1 + m2
      }
    }

    def vProg(vid: VertexId, data: Int, update: Int): Int = {
      if (update < 0) {
        // if the vertex has turned off, keep it turned off
        -1
      } else {
        // subtract the number of neighbors that have turned off this round from
        // the count of active vertices
        // TODO(crankshaw) can we ever have the case data < update?
        max(data - update, 0)
      }
    }

    // Note that initial message should have no effect
    Pregel(graph, 0)(vProg, sendMsg, mergeMsg)
  }
}
