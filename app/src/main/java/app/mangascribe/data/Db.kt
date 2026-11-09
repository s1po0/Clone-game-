package app.mangascribe.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A book: an imported chapter set, a folder of images, or a single page translated from the
 * share sheet. Pages keep their own state so a half-translated chapter resumes where it stopped.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    /** file | folder | share | demo */
    val origin: String,
    val coverPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastPageIndex: Int = 0,
    val pageCount: Int = 0,
    val folder: String? = null,
    val isFavorite: Boolean = false,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("bookId", "pageIndex", unique = true)],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    @ColumnInfo(name = "pageIndex") val index: Int,
    val imageUri: String,
    val width: Int = 0,
    val height: Int = 0,
    /** none | running | done | failed */
    val status: String = Status.NONE,
    val engine: String? = null,
    val error: String? = null,
    val translatedAt: Long? = null,
    /** JSON array of [BlockEntity]; stored inline so a page is one read, not N+1. */
    @ColumnInfo(name = "blocksJson") val blocksJson: String = "[]",
) {
    object Status {
        const val NONE = "none"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun find(id: Long): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE books SET lastPageIndex = :index, updatedAt = :now WHERE id = :id")
    suspend fun setLastPage(id: Long, index: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET isFavorite = 1 - isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId")
    suspend fun pageCount(bookId: Long): Int

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY pageIndex ASC")
    fun observePages(bookId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY pageIndex ASC")
    suspend fun pages(bookId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE bookId = :bookId AND pageIndex = :index LIMIT 1")
    suspend fun page(bookId: Long, index: Int): PageEntity?

    @Query("SELECT * FROM pages WHERE id = :pageId LIMIT 1")
    suspend fun pageById(pageId: Long): PageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("UPDATE pages SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String? = null)

    @Query(
        "UPDATE pages SET status = :status, blocksJson = :blocks, engine = :engine, " +
            "error = :error, translatedAt = :at, width = :w, height = :h WHERE id = :id"
    )
    suspend fun saveTranslation(
        id: Long,
        status: String,
        blocks: String,
        engine: String?,
        error: String?,
        at: Long,
        w: Int,
        h: Int,
    )

    @Query("UPDATE pages SET blocksJson = :blocks WHERE id = :id")
    suspend fun updateBlocks(id: Long, blocks: String)

}

@Database(entities = [BookEntity::class, PageEntity::class], version = 1, exportSchema = true)
abstract class MangaScribeDatabase : RoomDatabase() {
    abstract fun books(): BookDao

    companion object {
        const val NAME = "mangascribe.db"
    }
}
