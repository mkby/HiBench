package com.intel.sparkbench.nweight 

import scala.collection.JavaConversions._
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.HashPartitioner
import org.apache.spark.storage.StorageLevel
import org.apache.spark.graphx._
import org.apache.spark.graphx.impl.GraphImpl
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap

/** * Compute NWeight for Graph G(V, E) as defined below *     Weight(1)(u, v) = edge(u, v)
 *     Weight(n)(u, v) = Sum (over {x|there are edges (u, x) and (x, v)}) Weight(n-1)(u, x)*Weight(1)(x, v)
 *
 * Input is given in Text file format. Each line represents a Node and all out edges of that node (edge weight specified)
 *  <vertex> <vertex1>:<weight1>, <vertex2>:<weight2> ...)
 */

object GraphxNWeight extends Serializable{

  def mapF(edge: EdgeTriplet[SizedPriorityQueue, Double]) = {
    val m = new Long2DoubleOpenHashMap()
    val w1 = edge.attr
    val id = edge.srcId
    edge.dstAttr.foreach{ case (target, wn) =>
      if (target != id)
        m.put(target, wn*w1)
    }
    Iterator((id, m))
  }

  def reduceF(c1: Long2DoubleOpenHashMap, c2: Long2DoubleOpenHashMap) = {
    c2.long2DoubleEntrySet()
      .fastIterator()
      .foreach(pair => c1.put(pair.getLongKey(), c1.get(pair.getLongKey()) + pair.getDoubleValue()))
    c1
  }

  def updateF (id: VertexId, vdata: SizedPriorityQueue, msg: Option[Long2DoubleOpenHashMap]) = {
    vdata.clear()
    val weightMap = msg.orNull
    if (weightMap != null) {
      weightMap.long2DoubleEntrySet().fastIterator().foreach { pair =>
        val src = pair.getLongKey()
        val wn = pair.getDoubleValue()
        vdata.enqueue((src, wn))
      }
    }
    vdata
  }

  def nweight(sc: SparkContext, input: String, output: String, step: Int,
    maxDegree: Int, numPartitions: Int, storageLevel: StorageLevel) {

    //val start1 = System.currentTimeMillis
    val part = new HashPartitioner(numPartitions)
    val edges = sc.textFile(input, numPartitions).flatMap { line =>
      val fields = line.split("\\s+", 2)
      val src = fields(0).trim.toLong

      fields(1).split("[,\\s]+").filter(_.isEmpty() == false).map { pairStr =>
        val pair = pairStr.split(":")
        val (dest, weight) = (pair(0).trim.toLong, pair(1).toDouble)
        (src, Edge(src, dest, weight))
      }
    }.partitionBy(part).map(_._2)

    val vertices = edges.map { e =>
      (e.srcId, (e.dstId, e.attr))
    }.groupByKey(part).map { case (id, seq) =>
      val vdata = new SizedPriorityQueue(maxDegree)
      seq.foreach(vdata.enqueue(_))
      (id, vdata)
    }

    var g = GraphImpl(vertices, edges, new SizedPriorityQueue(maxDegree), storageLevel, storageLevel).cache()

    var msg: RDD[(VertexId, Long2DoubleOpenHashMap)] = null
    for (i <- 2 to step) {
      msg = g.mapReduceTriplets(mapF _, reduceF _, Some(g.vertices , EdgeDirection.In))
      g = g.outerJoinVertices(msg)(updateF _).persist(storageLevel)
    }

    g.vertices.map { case (vid, vdata) => 
      var s = new StringBuilder
      s.append(vid)

      vdata.foreach { r =>
        s.append(' ')
        s.append(r._1)
        s.append(':')
        s.append(r._2)
      }
      s.toString
    }.saveAsTextFile(output)
  }
}

