package com.example.lalem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val metals = listOf(Color(0xFFD7992E), Color(0xFF728BA0), Color(0xFFA96847))
private val podiumFills = listOf(Color(0xFFFFDB7D), Color(0xFFCAD9E3), Color(0xFFF1BF9C))
private val awardNames = listOf("冠军", "亚军", "季军")
private val stateColors = listOf(Color(0xFF428466), Color(0xFFD49C36), Color(0xFFB66A69))
private val chartDate = DateTimeFormatter.ofPattern("M/d")

@Composable
internal fun RankingPodium(ranks: List<LalemViewModel.Rank>) {
    val leaders = ranks.take(3).takeWhile { it.count > 0 }
    if (leaders.isEmpty()) {
        Text("有打卡记录后即可登上领奖台", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(1, 0, 2).forEach { index ->
            val rank = leaders.getOrNull(index)
            val place = rank?.place ?: index + 1
            val medal = place - 1
            val height = when (place) {
                1 -> 86.dp
                2 -> 66.dp
                else -> 50.dp
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (rank != null) {
                    val award = awardNames[medal]
                    Trophy(
                        Modifier.size(if (place == 1) 46.dp else 36.dp)
                            .semantics { contentDescription = award + "奖杯" },
                        metals[medal]
                    )
                    Text(if (rank.tied) "并列$award" else award, style = MaterialTheme.typography.labelMedium)
                    Text(
                        rank.displayName(), Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("${rank.count} 次", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                }
                val fill = if (rank == null) Color(0xFFEBF3E8) else podiumFills[medal]
                Box(
                    Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(Brush.verticalGradient(listOf(fill, fill.copy(alpha = 0.66f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        place.toString(), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = if (rank == null) Color(0xFF647466) else Color(0xFF392D22)
                    )
                }
            }
        }
    }
    ranks.drop(leaders.size).forEach { rank ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val position = when {
                rank.place == 0 -> "—"
                rank.tied -> "并列 ${rank.place}"
                else -> rank.place.toString()
            }
            Text(position, Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium)
            Text(rank.displayName(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${rank.count} 次", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun LalemViewModel.Rank.displayName() =
    if (self) "我" else name.ifBlank { "好友" }

@Composable
internal fun Trophy(modifier: Modifier, metal: Color) {
    Canvas(modifier) {
        val unit = size.minDimension / 40f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        val handles = Path().apply {
            moveTo(10f * unit, 9f * unit)
            lineTo(4f * unit, 9f * unit)
            cubicTo(4f * unit, 17f * unit, 8f * unit, 20f * unit, 14f * unit, 20f * unit)
            moveTo(30f * unit, 9f * unit)
            lineTo(36f * unit, 9f * unit)
            cubicTo(36f * unit, 17f * unit, 32f * unit, 20f * unit, 26f * unit, 20f * unit)
        }
        drawPath(handles, metal, style = Stroke(width = 2.5f * unit))
        val cup = Path().apply {
            moveTo(9f * unit, 5f * unit)
            lineTo(31f * unit, 5f * unit)
            cubicTo(31f * unit, 17f * unit, 28f * unit, 25f * unit, 20f * unit, 25f * unit)
            cubicTo(12f * unit, 25f * unit, 9f * unit, 17f * unit, 9f * unit, 5f * unit)
            close()
        }
        drawPath(cup, Brush.horizontalGradient(listOf(metal, Color.White.copy(alpha = 0.85f), metal), 8f * unit, 32f * unit))
        drawRect(metal, point(18f, 24f), Size(4f * unit, 9f * unit))
        drawRect(metal, point(13f, 32f), Size(14f * unit, 3f * unit))
        drawRect(Color(0xFF344D43), point(10f, 35f), Size(20f * unit, 4f * unit))
    }
}

@Composable
internal fun StatisticsScreen(records: List<Entry>) {
    var weekly by rememberSaveable { mutableStateOf(true) }
    val today = LocalDate.now()
    val days = if (weekly) 7 else 30
    val dates = List(days) { today.minusDays((days - 1L) - it) }
    val recent = records.filter { it.date in dates.first()..today }
    val byDate = recent.groupingBy { it.date }.eachCount()
    val daily = dates.map { byDate[it] ?: 0 }
    var selected by rememberSaveable(weekly, today.toEpochDay()) { mutableIntStateOf(dates.lastIndex) }
    val scale = daily.maxOrNull()?.coerceAtLeast(1) ?: 1
    PageTitle("统计")
    PeriodChips(weekly, { weekly = it }, "近 7 天", "近 30 天")
    Panel(accent = true) {
        Text("累计打卡", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text("${recent.size} 次", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("${dates.first().format(chartDate)} – ${today.format(chartDate)}",
                style = MaterialTheme.typography.labelLarge)
        }
    }
    Panel {
        Text("每日打卡趋势", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "${dates[selected].format(chartDate)}   ·   ${daily[selected]} 次",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge
        )
        Row(Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(Modifier.width(30.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(scale.toString(), style = MaterialTheme.typography.labelSmall)
                Text("0", style = MaterialTheme.typography.labelSmall)
            }
            val guide = MaterialTheme.colorScheme.surfaceVariant
            val primary = MaterialTheme.colorScheme.primary
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(Modifier.matchParentSize()) {
                    listOf(0f, .5f, 1f).forEach { fraction ->
                        val y = fraction * (size.height - 1f)
                        drawLine(guide, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    daily.forEachIndexed { index, count ->
                        val active = selected == index
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .semantics { contentDescription = "${dates[index].format(chartDate)}，$count 次" }
                                .clickable { selected = index },
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            val bar = Modifier.fillMaxWidth(if (weekly) .54f else .68f)
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            if (count > 0) {
                                Box(bar.fillMaxHeight(count.toFloat() / scale).background(
                                    if (active) primary else Color(0xFF91BDA3)
                                ))
                            } else if (active) {
                                Box(bar.height(3.dp).background(primary))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 35.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(dates.first(), dates[dates.size / 2], dates.last()).forEach {
                Text(it.format(chartDate), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Panel {
        Text("状态分布", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        val counts = RecordType.entries.map { type -> recent.count { it.type == type } }
        val emptyRing = MaterialTheme.colorScheme.surfaceVariant
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(7.dp)) {
                    val thickness = 12.dp.toPx()
                    drawCircle(emptyRing, style = Stroke(thickness))
                    var start = -90f
                    counts.forEachIndexed { index, count ->
                        if (count > 0) {
                            val sweep = 360f * count / recent.size
                            drawArc(stateColors[index], start, sweep, false, style = Stroke(thickness))
                            start += sweep
                        }
                    }
                }
                Text("${recent.size} 次", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RecordType.entries.forEachIndexed { index, type ->
                    Row(
                        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(stateColors[index]))
                        Text(type.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${counts[index]} 次", textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
