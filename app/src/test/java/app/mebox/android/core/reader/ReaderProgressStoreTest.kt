package app.mebox.android.core.reader

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterDto
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderProgressStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = AccountScope("server", "user")
    private val json = Json { encodeDefaults = true }
    private fun event(position: Int = 8, time: Long = 200) = ReaderProgress(
        bookId = "book", chapterIndex = 1, position = position, chapterTitle = "第二章",
        chapterUrl = "chapter-2", updatedAtMs = time,
    )

    @Test fun `pending position survives reconstructing store and backward reading`() {
        val root = temporary.newFolder()
        val store = ReaderProgressStore(root, json)
        store.save(scope, event(40, 100))
        store.save(scope, event(3, 200))
        val recovered = ReaderProgressStore(root, json).load(scope, "book")!!
        assertEquals(3, recovered.position)
        assertTrue(recovered.pending)
        assertEquals(1, ReaderProgressStore(root, json).pending(scope).size)
    }

    @Test fun `acknowledging old request never acknowledges newer local event`() {
        val store = ReaderProgressStore(temporary.newFolder(), json)
        val first = event()
        store.save(scope, first)
        val next = event(2, 201)
        store.save(scope, next)
        store.acknowledge(scope, first)
        assertEquals(next.eventId, store.pending(scope).single().eventId)
        store.acknowledge(scope, next)
        assertTrue(store.pending(scope).isEmpty())
        assertEquals(2, store.load(scope, "book")!!.position)
    }

    @Test fun `late disk write cannot replace newer event`() {
        val store = ReaderProgressStore(temporary.newFolder(), json)
        store.save(scope, event(2, 300))
        store.save(scope, event(10, 200))
        assertEquals(2, store.load(scope, "book")!!.position)
    }

    @Test fun `clearing an account invalidates already captured disk writes`() {
        val store = ReaderProgressStore(temporary.newFolder(), json)
        val generation = store.generation(scope)
        store.save(scope, event(), generation)
        store.clearAccount(scope)
        store.save(scope, event(9, 300), generation)
        assertNull(store.load(scope, "book"))
        store.save(scope, event(2, 400), store.generation(scope))
        assertEquals(2, store.load(scope, "book")!!.position)
    }

    @Test fun `account and server identifiers cannot collide or traverse`() {
        val store = ReaderProgressStore(temporary.newFolder(), json)
        store.save(AccountScope("a:b", "c"), event())
        store.save(AccountScope("..", "user"), event(4))
        assertNull(store.load(AccountScope("a", "b:c"), "book"))
        assertNull(store.load(scope, "book"))
        store.clearAccount(AccountScope("..", "user"))
        assertNotNull(store.load(AccountScope("a:b", "c"), "book"))
    }

    @Test fun `newer checkpoint is relocated by chapter URL after TOC changes`() {
        val book = ReaderBookDto(id = "book", durChapterTime = 100)
        val chapters = listOf(ReaderChapterDto(url = "new"), ReaderChapterDto(url = "other"), ReaderChapterDto(url = "chapter-2"))
        val merged = ReaderProgressResume.merge(book, event(3), chapters)
        assertEquals(2, merged.durChapterIndex)
        assertEquals(3, merged.durChapterPos)
    }

    @Test fun `missing chapter or newer server progress keeps server position`() {
        val book = ReaderBookDto(id = "book", durChapterIndex = 0, durChapterPos = 5, durChapterTime = 100)
        assertEquals(book, ReaderProgressResume.merge(book, event(), listOf(ReaderChapterDto(url = "other"))))
        assertEquals(book, ReaderProgressResume.merge(book, event(time = 50)))
        assertEquals(book, ReaderProgressResume.merge(book, event().copy(bookId = "another")))
    }
}
