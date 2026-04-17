package core.io

import core.model.*
import core.model.Project as CoreProject
import core.model.Track as CoreTrack
import core.model.Note as CoreNote
import core.util.nameWithoutExtension
import core.util.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import core.process.validateNotes
import kotlin.math.pow

object Dsc {
    private const val TICKS_PER_BEAT = 480L

    private val jsonSerializer = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    suspend fun parse(
        file: File,
        params: ImportParams,
    ): CoreProject {
        val text = file.readText()
        val project = jsonSerializer.decodeFromString(DscProject.serializer(), text)
        val warnings = mutableListOf<ImportWarning>()

        val tracks = parseTracks(project, params)
        val mainTrack = project.tracks.firstOrNull()
        val bpm = mainTrack?.bpm ?: 120.0
        val tempos = listOf(Tempo(tickPosition = 0L, bpm = bpm))
        val timeSignatures = listOf(TimeSignature.default)
        
        return CoreProject(
            format = format,
            inputFiles = listOf(file),
            name = project.name.takeUnless { it.isNullOrBlank() } ?: file.nameWithoutExtension,
            tracks = tracks,
            timeSignatures = timeSignatures,
            tempos = tempos,
            measurePrefix = 0,
            importWarnings = warnings,
        )
    }

    private fun parseTracks(
        project: DscProject,
        params: ImportParams,
    ): List<CoreTrack> {
        val roleToNotes = mutableMapOf<String, MutableList<CoreNote>>()
        val roleToPitchPoints = mutableMapOf<String, MutableList<Pair<Long, Double>>>()
        
        var currentStartTick = 0L
        var prevStartTick = 0L

        for (track in project.tracks) {
            val isInstrumental = track.isInstrumental
            val concurrent = track.concurrent
            
            val startTick = if (concurrent) prevStartTick else currentStartTick
            prevStartTick = startTick
            
            var trackDurationTicks = 0L
            val trackNotes = mutableListOf<CoreNote>()
            var localTick = 0L
            
            val transposition = track.keySignature - 60
            
            for (note in track.notes) {
                val lengthInTicks = (note.duration * TICKS_PER_BEAT).toLong()
                val pronunciation = note.pronunciation
                val isRest = pronunciation?.isRest ?: true
                
                if (!isRest) {
                    val lyric = pronunciation?.nativeSyllable?.takeUnless { it.isNullOrBlank() || it == "、" }
                        ?: pronunciation?.displayText?.takeUnless { it.isNullOrBlank() || it == "、" }
                        ?: pronunciation?.originalText?.takeUnless { it.isNullOrBlank() || it == "、" }
                        ?: params.defaultLyric

                    // Apply Key Signature transposition directly to Note pitch
                    trackNotes.add(
                        CoreNote(
                            id = 0,
                            key = (note.pitch + transposition + 0.5).toInt(),
                            lyric = lyric,
                            tickOn = startTick + localTick,
                            tickOff = startTick + localTick + lengthInTicks,
                        )
                    )
                }
                localTick += lengthInTicks
                trackDurationTicks += lengthInTicks
            }
            
            currentStartTick = startTick + trackDurationTicks
            
            if (isInstrumental) continue
            val role = track.roles.firstOrNull() ?: ""
            roleToNotes.getOrPut(role) { mutableListOf() }.addAll(trackNotes)
            
            if (!params.simpleImport) {
                val trackPitchPoints = parsePitch(track, startTick, transposition.toDouble())
                roleToPitchPoints.getOrPut(role) { mutableListOf() }.addAll(trackPitchPoints)
            }
        }
        
        var trackId = 0
        return roleToNotes.map { (role, notes) ->
            val trackName = if (role.isNotBlank()) role else "Track ${trackId + 1}"
            val pitchObj = if (!params.simpleImport) {
                val points = roleToPitchPoints[role] ?: emptyList()
                val sortedPoints = points.sortedBy { it.first }.distinctBy { it.first }
                if (sortedPoints.isNotEmpty()) {
                    Pitch(sortedPoints, isAbsolute = false)
                } else null
            } else null
            
            CoreTrack(
                id = trackId++,
                name = trackName,
                notes = notes.sortedBy { it.tickOn },
                pitch = pitchObj,
            ).validateNotes()
        }
    }

    private fun lerp(y0: Double, y1: Double, x0: Double, x1: Double, x: Double): Double {
        if (x1 == x0) return y0
        return y0 + (x - x0) * (y1 - y0) / (x1 - x0)
    }

    private fun applyTrillSpeedGradual(timeOffset: Double, timeToPeak: Double, timeFromPeak: Double, gradual: Double): Double {
        val v = if (gradual < 0) lerp(1.0/3.0, 1.0, -1.0, 0.0, gradual) else lerp(1.0, 3.0, 0.0, 1.0, gradual)
        val v2 = if (gradual < 0) lerp(3.0, 1.0, -1.0, 0.0, gradual) else lerp(1.0, 1.0/3.0, 0.0, 1.0, gradual)
        
        if (timeOffset <= timeToPeak) {
            val v3 = if (timeToPeak == 0.0) 1.0 else lerp(v, 1.0, 0.0, timeToPeak, timeOffset)
            return (v + v3) / 2.0 * timeOffset
        } else {
            val v4 = if (timeFromPeak == 0.0) 1.0 else lerp(1.0, v2, timeToPeak, timeToPeak + timeFromPeak, timeOffset)
            return (v + 1.0) / 2.0 * timeToPeak + (1.0 + v4) / 2.0 * (timeOffset - timeToPeak)
        }
    }

    private fun evaluateEnvelope(x: Double, start: Double, peak: Double, end: Double, sharpness: Double, peakValue: Double): Double {
        if (x <= start || x >= end) return 0.0
        var ratio = 0.0
        if (x <= peak) {
            if (kotlin.math.abs(peak - start) > 1e-12) {
                ratio = -kotlin.math.PI * (peak - x) / (peak - start)
            }
        } else {
            if (kotlin.math.abs(peak - end) > 1e-12) {
                ratio = kotlin.math.PI * (x - peak) / (end - peak)
            }
        }
        var y = (1.0 + kotlin.math.cos(ratio)) / 2.0
        y = kotlin.math.abs(y).let { it.pow(sharpness) }
        return y * peakValue
    }

    private fun sampleCoreParams(localTick: Long, lengthInTicks: Long, params: List<DscParamSubSegment>): Double {
        val tRatio = localTick.toDouble() / lengthInTicks
        var accumulated = 0.0
        for (sub in params) {
            val segPercent = (sub.durationPermille ?: 1000.0) / 1000.0
            if (tRatio >= accumulated && tRatio <= accumulated + segPercent) {
                val segLocalRatio = (tRatio - accumulated) / segPercent
                val st = sub.startPointTime ?: 0.0
                val et = sub.endPointTime ?: 0.0
                val sf = sub.startPointFreq ?: 0.0
                val ef = sub.endPointFreq ?: 0.0
                
                if (segLocalRatio <= st && st > 0) {
                    val progress = segLocalRatio / st
                    // smooth step from sf to 0
                    return sf * (1.0 - progress)
                } else if (segLocalRatio >= (1.0 + et) && et < 0) {
                    val progress = (segLocalRatio - (1.0 + et)) / (-et)
                    // smooth step from 0 to ef
                    return ef * progress
                } else {
                    return 0.0
                }
            }
            accumulated += segPercent
        }
        return 0.0
    }

    private fun parsePitch(track: DscTrack, startTick: Long, transposition: Double): List<Pair<Long, Double>> {
        val convertedPoints = mutableListOf<Pair<Long, Double>>()
        var currentLocalTick = 0L
        
        val trackDurationTicks = track.notes.sumOf { (it.duration * TICKS_PER_BEAT).toLong() }
        if (trackDurationTicks == 0L) return emptyList()
        
        // Prepare note timing mapping
        val noteBounds = mutableListOf<Triple<Long, Long, DscNote>>() // start, end, note
        var tAccum = 0L
        for (note in track.notes) {
            val len = (note.duration * TICKS_PER_BEAT).toLong()
            noteBounds.add(Triple(tAccum, tAccum + len, note))
            tAccum += len
        }
        
        val step = 5L
        for (t in 0 until trackDurationTicks step step) {
            val beat = t.toDouble() / TICKS_PER_BEAT
            var offset = 0.0
            
            // coreParams (核心发音小段数组) and trailingSegment are formant/timbre parameters,
            // NOT pitch modifications. Only skills (frequency, trill) affect pitch.
            
            // Evaluate track skills
            if (track.skills != null) {
                for (skill in track.skills!!) {
                    if (beat >= skill.start && beat <= skill.end) {
                        val envelope = evaluateEnvelope(beat, skill.start, skill.peakTime, skill.end, skill.sharpness, skill.peakValue)
                        if (skill.type == "frequency") {
                            offset += envelope * 12.0 * (if (skill.freqIncrease != false) 1.0 else -1.0)
                        } else if (skill.type == "trill") {
                            val warpedTime = applyTrillSpeedGradual(
                                beat - skill.start, 
                                skill.peakTime - skill.start, 
                                skill.end - skill.peakTime, 
                                skill.trillSpeedGradual
                            )
                            val phasePure = warpedTime * 6.0 * 2.0 * kotlin.math.PI * skill.trillSpeed
                            val phaseShifted = phasePure + skill.trillPhase * 2.0 * kotlin.math.PI
                            val customWave = kotlin.math.sin(phaseShifted)
                            val pureWave = kotlin.math.sin(phasePure)
                            val trillWave = customWave * (1.0 - skill.trillSineRatio) + pureWave * skill.trillSineRatio
                            offset += trillWave * envelope * 12.0
                        }
                    }
                }
            }
            
            // Compensate for microtonal rounding: note.pitch can be fractional (non-12TET),
            // but CoreNote.key is integer. Embed the fractional remainder into the pitch curve.
            val currentNote = noteBounds.firstOrNull { t >= it.first && t < it.second }
            if (currentNote != null) {
                val note = currentNote.third
                val rawPitch = note.pitch + transposition
                val quantizedKey = (rawPitch + 0.5).toInt()
                offset += rawPitch - quantizedKey.toDouble()
            }
            
            if (offset != 0.0) {
                convertedPoints.add((startTick + t) to offset)
            }
        }
        
        return convertedPoints
    }

    fun generate(
        project: CoreProject,
        features: List<FeatureConfig>,
    ): ExportResult {
        val jsonText = generateContent(project, features)
        val blob = Blob(arrayOf(jsonText), BlobPropertyBag("application/octet-stream"))
        val name = format.getFileName(project.name)
        return ExportResult(
            blob,
            name,
            listOfNotNull(
                if (features.contains(Feature.ConvertPitch)) ExportNotification.PitchDataExported else null,
            ),
        )
    }

    private fun generateContent(
        project: CoreProject,
        features: List<FeatureConfig>,
    ): String {
        val template = core.external.Resources.dscTemplate
        val dsc = jsonSerializer.decodeFromString(DscProject.serializer(), template)
        
        dsc.name = project.name
        dsc.filename = project.name
        
        val emptyTrack = dsc.tracks.firstOrNull() ?: DscTrack()
        
        dsc.tracks = project.tracks.map {
            val mainTempo = project.tempos.firstOrNull()?.bpm ?: 120.0
            generateTrack(it, emptyTrack.copy(bpm = mainTempo), features)
        }
        
        return jsonSerializer.encodeToString(DscProject.serializer(), dsc)
    }

    private fun generateTrack(
        track: CoreTrack,
        emptyTrack: DscTrack,
        features: List<FeatureConfig>,
    ): DscTrack {
        val dscNotes = mutableListOf<DscNote>()
        var currentTick = 0L
        
        for (note in track.notes) {
            if (note.tickOn > currentTick) {
                val restLen = note.tickOn - currentTick
                dscNotes.add(
                    DscNote(
                        pitch = 60.0,
                        duration = restLen.toDouble() / TICKS_PER_BEAT,
                        pronunciation = DscPronunciation(isRest = true, originalText = "、", displayText = "、")
                    )
                )
            }
            val noteDuration = note.length.toDouble() / TICKS_PER_BEAT
            dscNotes.add(
                DscNote(
                    pitch = note.key.toDouble(),
                    duration = noteDuration,
                    pronunciation = DscPronunciation(
                        isRest = false,
                        originalText = note.lyric,
                        displayText = note.lyric
                    )
                )
            )
            currentTick = note.tickOff
        }

        return emptyTrack.copy(notes = dscNotes)
    }

    @Serializable
    private data class DscProject(
        @kotlinx.serialization.SerialName("歌曲名称") var name: String? = null,
        @kotlinx.serialization.SerialName("文件名") var filename: String? = null,
        @kotlinx.serialization.SerialName("声乐曲") var tracks: List<DscTrack> = listOf(),
    )

    @Serializable
    private data class DscTrack(
        @kotlinx.serialization.SerialName("每分钟拍数") var bpm: Double? = null,
        @kotlinx.serialization.SerialName("音符") var notes: List<DscNote> = listOf(),
        @kotlinx.serialization.SerialName("声乐声带") var vocalCords: List<JsonElement>? = null,
        @kotlinx.serialization.SerialName("角色") var roles: List<String> = listOf(""),
        @kotlinx.serialization.SerialName("纯音乐") var isInstrumental: Boolean = false,
        @kotlinx.serialization.SerialName("跟随上一行一起播放") var concurrent: Boolean = false,
        @kotlinx.serialization.SerialName("调号") var keySignature: Int = 60,
        @kotlinx.serialization.SerialName("技巧") var skills: List<DscSkill>? = null,
    )

    @Serializable
    private data class DscNote(
        @kotlinx.serialization.SerialName("音高") var pitch: Double = 60.0,
        @kotlinx.serialization.SerialName("时长") var duration: Double = 1.0,
        @kotlinx.serialization.SerialName("音节发音") var pronunciation: DscPronunciation? = null,
    )

    @Serializable
    private data class DscPronunciation(
        @kotlinx.serialization.SerialName("休止符") var isRest: Boolean = false,
        @kotlinx.serialization.SerialName("原生表音法的音节") var nativeSyllable: String? = null,
        @kotlinx.serialization.SerialName("原文") var originalText: String? = null,
        @kotlinx.serialization.SerialName("音符显示") var displayText: String? = null,
        @kotlinx.serialization.SerialName("发音详细参数") var paramDetails: DscParamDetails? = null,
    )

    @Serializable
    private data class DscParamDetails(
        @kotlinx.serialization.SerialName("核心发音小段数组") var coreParams: List<DscParamSubSegment>? = null,
        @kotlinx.serialization.SerialName("后面的音节过度发音小段") var trailingSegment: DscParamSubSegment? = null,
    )

    @Serializable
    private data class DscParamSubSegment(
        @kotlinx.serialization.SerialName("持续时间") var durationPermille: Double? = null,
        @kotlinx.serialization.SerialName("开始控制点时间") var startPointTime: Double? = null,
        @kotlinx.serialization.SerialName("开始控制点频率") var startPointFreq: Double? = null,
        @kotlinx.serialization.SerialName("结束控制点时间") var endPointTime: Double? = null,
        @kotlinx.serialization.SerialName("结束控制点频率") var endPointFreq: Double? = null,
    )

    @Serializable
    private data class DscSkill(
        @kotlinx.serialization.SerialName("类型") var type: String = "",
        @kotlinx.serialization.SerialName("开始") var start: Double = 0.0,
        @kotlinx.serialization.SerialName("峰的时间") var peakTime: Double = 0.0,
        @kotlinx.serialization.SerialName("结束") var end: Double = 0.0,
        @kotlinx.serialization.SerialName("峰值") var peakValue: Double = 0.0,
        @kotlinx.serialization.SerialName("峰尖锐") var sharpness: Double = 1.0,
        @kotlinx.serialization.SerialName("频率增加") var freqIncrease: Boolean? = null,
        @kotlinx.serialization.SerialName("颤音速度") var trillSpeed: Double = 1.0,
        @kotlinx.serialization.SerialName("颤音相位") var trillPhase: Double = 0.0,
        @kotlinx.serialization.SerialName("颤音速度渐变") var trillSpeedGradual: Double = 0.0,
        @kotlinx.serialization.SerialName("颤音正弦比例") var trillSineRatio: Double = 1.0,
    )

    private val format = Format.Dsc
}
