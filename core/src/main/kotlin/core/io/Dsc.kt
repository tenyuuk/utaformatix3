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
        return project.tracks.mapIndexed { index, track ->
            val notes = parseNotes(track, params.defaultLyric)
            val pitch = if (params.simpleImport) null else parsePitch(track)
            CoreTrack(
                id = index,
                name = "Track ${index + 1}",
                notes = notes,
                pitch = pitch,
            ).validateNotes()
        }
    }

    private fun parseNotes(track: DscTrack, defaultLyric: String): List<CoreNote> {
        val notes = mutableListOf<CoreNote>()
        var currentTick = 0L
        for (note in track.notes) {
            val lengthInTicks = (note.duration * TICKS_PER_BEAT).toLong()
            val pronunciation = note.pronunciation
            val isRest = pronunciation?.isRest ?: true
            
            if (!isRest) {
                val lyric = pronunciation?.nativeSyllable?.takeUnless { it.isNullOrBlank() || it == "、" }
                    ?: pronunciation?.displayText?.takeUnless { it.isNullOrBlank() || it == "、" }
                    ?: pronunciation?.originalText?.takeUnless { it.isNullOrBlank() || it == "、" }
                    ?: defaultLyric

                notes.add(
                    CoreNote(
                        id = 0,
                        key = (note.pitch + 0.5).toInt(),
                        lyric = lyric,
                        tickOn = currentTick,
                        tickOff = currentTick + lengthInTicks,
                    )
                )
            }
            currentTick += lengthInTicks
        }
        return notes
    }

    private fun parsePitch(track: DscTrack): Pitch? {
        val convertedPoints = mutableListOf<Pair<Long, Double>>()
        var currentTick = 0L
        
        for (note in track.notes) {
            val lengthInTicks = (note.duration * TICKS_PER_BEAT).toLong()
            val pronunciation = note.pronunciation
            val isRest = pronunciation?.isRest ?: true
            val paramDetails = pronunciation?.paramDetails
            
            if (!isRest && paramDetails != null) {
                var segmentAccumulatedPercent = 0.0
                val array = paramDetails.coreParams
                if (array != null) {
                   for (subParam in array) {
                       val segPercent = (subParam.durationPermille ?: 1000.0) / 1000.0
                       val segLength = segPercent * lengthInTicks
                       val segStartTick = currentTick + (segmentAccumulatedPercent * lengthInTicks).toLong()
                       
                       val st = subParam.startPointTime ?: 0.0
                       val et = subParam.endPointTime ?: 0.0
                       val sf = (subParam.startPointFreq ?: 0.0) * 1000.0
                       val ef = (subParam.endPointFreq ?: 0.0) * 1000.0
                       
                       val startTick = segStartTick
                       val startZeroTick = segStartTick + (st * segLength).toLong()
                       val endZeroTick = segStartTick + ((1.0 + et) * segLength).toLong()
                       val endTick = segStartTick + segLength.toLong()
                       
                       if (sf != 0.0) {
                           convertedPoints.add(startTick to sf)
                           convertedPoints.add(startZeroTick to 0.0)
                       }
                       if (ef != 0.0) {
                           convertedPoints.add(endZeroTick to 0.0)
                           convertedPoints.add(endTick to ef)
                       }
                       segmentAccumulatedPercent += segPercent
                   }
                }
                
                val trailingParams = paramDetails.trailingSegment
                if (trailingParams != null) {
                   val segPercent = (trailingParams.durationPermille ?: 1000.0) / 1000.0
                   val segLength = segPercent * lengthInTicks
                   val segStartTick = currentTick
                   val st = trailingParams.startPointTime ?: 0.0
                   val et = trailingParams.endPointTime ?: 0.0
                   val sf = (trailingParams.startPointFreq ?: 0.0) * 1000.0
                   val ef = (trailingParams.endPointFreq ?: 0.0) * 1000.0
                   
                   val startTick = segStartTick
                   val startZeroTick = segStartTick + (st * segLength).toLong()
                   val endZeroTick = segStartTick + ((1.0 + et) * segLength).toLong()
                   val endTick = segStartTick + segLength.toLong()
                   
                   if (sf != 0.0) {
                       convertedPoints.add(startTick to sf)
                       convertedPoints.add(startZeroTick to 0.0)
                   }
                   if (ef != 0.0) {
                       convertedPoints.add(endZeroTick to 0.0)
                       convertedPoints.add(endTick to ef)
                   }
                }
            }
            currentTick += lengthInTicks
        }
        
        return Pitch(convertedPoints.sortedBy { it.first }.distinctBy { it.first }, isAbsolute = false).takeIf { it.data.isNotEmpty() }
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

    private val format = Format.Dsc
}
