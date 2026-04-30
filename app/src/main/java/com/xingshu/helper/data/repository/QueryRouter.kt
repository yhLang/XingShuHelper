package com.xingshu.helper.data.repository

object QueryRouter {

    enum class RouteType { STRUCTURED, TALK_SCRIPT }

    // 匹配结构化查询的关键词/模式：价格、时段、地址、老师排班
    private val structuredPatterns = listOf(
        Regex("多少钱|价格|收费|学费|费用|课包|价钱|报价|多少元|元/节|元一节"),
        Regex("上课时间|时间段|时段|几点|周[一二三四五六七日天]|什么时候上课|课程时间|排课|开课时间"),
        Regex("地址|在哪里|在哪|位置|怎么去|怎么走|哪个地方|哪里上课|路在哪"),
        Regex("哪个老师|哪位老师|老师时间|老师排班|老师安排|老师有没有课"),
    )

    fun route(query: String): RouteType {
        return if (structuredPatterns.any { it.containsMatchIn(query) }) {
            RouteType.STRUCTURED
        } else {
            RouteType.TALK_SCRIPT
        }
    }
}
