package com.aiguigu.apitest

import org.apache.flink.api.common.functions.ReduceFunction
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.streaming.api.functions.source.SourceFunction
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer011

/**
 * map flatMap filter 简单转换算子
 * keyBy rolling reduce 聚合转换算子 键控流转换算子
 * split select connect coMap union 多流转换算子
 */

object TransformTest {
  def main(args: Array[String]): Unit = {
    // 1. map 来一个转换为1个 1对1的转换
    // 2. flatMap 打散 1对多
    // 3. filter 给一个bool函数, 判断是否该元素要保留
    // 4. keyBy 定义两个任务间数据传输的模式 DataStream -> KeyedStream
    // 5. 滚动聚合算子: sum/min/max/minBy/maxBy
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    //    env.setParallelism(1)
    // 0. 读取数据
    val inputPath = getClass.getResource("/sensor.txt").getPath
    val inputStream = env.readTextFile(inputPath)
    // 1. 先转换成SensorReading数据类型(简单转换操作)
    val dataStream = inputStream.map(
      data => {
        val arr = data.split(",")
        SensorReading(arr(0), arr(1).toLong, arr(2).toDouble)
      }
    )
    // 2. 分组聚合
    val aggStream = dataStream
      .keyBy("id") // 以id分组
      .minBy("temperature")

    // 3. 输出当前最小温度值以及最近的时间戳
    val resultStream = dataStream
      .keyBy("id")
      .reduce((curState, newData) => {
        SensorReading(curState.id, newData.timestamp, curState.temperature.min(newData.temperature))
      })
    //      .reduce(new MyReduceFunction())
    //    resultStream.print()

    // DataStream -> split -> SplitStream -> select -> DataStream
    // 4. 多流转换操作
    // 4.1 将传感器数据分为低温和高温两个流
    val splitStream = dataStream
      .split(data => {
        if (data.temperature > 30) Seq("high") else Seq("low")
      })

    val highTempStream = splitStream.select("high")
    val lowTempStream = splitStream.select("low")
    val allTempStream = splitStream.select("high", "low")
//    highTempStream.print("high")
//    lowTempStream.print("low")
//    allTempStream.print("all")

    // 4.2. 合流操作connect
    val warningStream = highTempStream.map(data => (data.id, data.temperature))

    val connectedStreams = warningStream.connect(lowTempStream)
    // 使用CoMap对数据进行分别处理

    val coMapResultStream = connectedStreams
      .map(
        warningData => (warningData._1, warningData._2, "warning"),
        lowTempData => (lowTempData.id, "healthy"))

    coMapResultStream.print()

    // 4.3 union 合流 要求两个流的数据类型相同

    env.execute("TransformTest")
  }
}

class MyReduceFunction extends ReduceFunction[SensorReading] {
  override def reduce(v1: SensorReading, v2: SensorReading): SensorReading = {
    SensorReading(v1.id, v2.timestamp, v1.temperature.min(v2.temperature))
  }
}
