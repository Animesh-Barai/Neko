package eu.kanade.tachiyomi.ui.library

import android.os.Bundle
import com.jakewharton.rxrelay.BehaviorRelay
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.utils.FollowStatus
import eu.kanade.tachiyomi.source.online.utils.MdUtil
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.combineLatest
import eu.kanade.tachiyomi.util.isNullOrUnsubscribed
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_EXCLUDE
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_IGNORE
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_INCLUDE
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Class containing library information.
 */
private data class Library(val categories: List<Category>, val mangaMap: LibraryMap)

/**
 * Typealias for the library manga, using the category as keys, and list of manga as values.
 */
private typealias LibraryMap = Map<Int, List<LibraryItem>>

/**
 * Presenter of [LibraryController].
 */
class LibraryPresenter(
    private val db: DatabaseHelper = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get()
) : BasePresenter<LibraryController>() {

    /**
     * Categories of the library.
     */
    var categories: List<Category> = emptyList()
        private set

    /**
     * Relay used to apply the UI filters to the last emission of the library.
     */
    private val filterTriggerRelay = BehaviorRelay.create(Unit)

    /**
     * Relay used to apply the UI update to the last emission of the library.
     */
    private val downloadTriggerRelay = BehaviorRelay.create(Unit)

    /**
     * Relay used to apply the selected sorting method to the last emission of the library.
     */
    private val sortTriggerRelay = BehaviorRelay.create(Unit)

    /**
     * Library subscription.
     */
    private var librarySubscription: Subscription? = null

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        subscribeLibrary()
    }

    /**
     * Subscribes to library if needed.
     */
    fun subscribeLibrary() {
        if (librarySubscription.isNullOrUnsubscribed()) {
            librarySubscription = getLibraryObservable()
                .combineLatest(downloadTriggerRelay.observeOn(Schedulers.io())) { lib, _ ->
                    lib.apply { setDownloadCount(mangaMap) }
                }
                .combineLatest(filterTriggerRelay.observeOn(Schedulers.io())) { lib, _ ->
                    lib.copy(mangaMap = applyFilters(lib.mangaMap))
                }
                .combineLatest(sortTriggerRelay.observeOn(Schedulers.io())) { lib, _ ->
                    lib.copy(mangaMap = applySort(lib.mangaMap))
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeLatestCache({ view, (categories, mangaMap) ->
                    view.onNextLibraryUpdate(categories, mangaMap)
                })
        }
    }

    /**
     * Applies library filters to the given map of manga.
     *
     * @param map the map to filter.
     */
    private fun applyFilters(map: LibraryMap): LibraryMap {
        val filterDownloaded = preferences.filterDownloaded().getOrDefault()

        val filterUnread = preferences.filterUnread().getOrDefault()

        val filterCompleted = preferences.filterCompleted().getOrDefault()

        val filterAnilist = preferences.filterAnilist().getOrDefault()

        val filterKitsu = preferences.filterKitsu().getOrDefault()

        val filterMyanimelist = preferences.filterMyAnimeList().getOrDefault()

        val filterFn: (LibraryItem) -> Boolean = f@{ item ->
            // Filter when there isn't unread chapters.
            if (filterUnread == STATE_INCLUDE && item.manga.unread == 0) return@f false
            if (filterUnread == STATE_EXCLUDE && item.manga.unread > 0) return@f false

            if (filterCompleted == STATE_INCLUDE && item.manga.status != SManga.COMPLETED)
                return@f false
            if (filterCompleted == STATE_EXCLUDE && item.manga.status == SManga.COMPLETED)
                return@f false

            if (filterAnilist != STATE_IGNORE) {
                val db = Injekt.get<DatabaseHelper>()
                val tracks = db.getTracks(item.manga).executeAsBlocking()
                val trackCount = tracks.count { it.sync_id == TrackManager.ANILIST }
                if (filterAnilist == STATE_INCLUDE && trackCount == 0) return@f false
                if (filterAnilist == STATE_EXCLUDE && trackCount > 0) return@f false
            }
            if (filterKitsu != STATE_IGNORE) {
                val db = Injekt.get<DatabaseHelper>()
                val tracks = db.getTracks(item.manga).executeAsBlocking()
                val trackCount = tracks.count { it.sync_id == TrackManager.KITSU }
                if (filterKitsu == STATE_INCLUDE && trackCount == 0) return@f false
                if (filterKitsu == STATE_EXCLUDE && trackCount > 0) return@f false
            }
            if (filterMyanimelist != STATE_IGNORE) {
                val db = Injekt.get<DatabaseHelper>()
                val tracks = db.getTracks(item.manga).executeAsBlocking()
                val trackCount = tracks.count { it.sync_id == TrackManager.MYANIMELIST }
                if (filterMyanimelist == STATE_INCLUDE && trackCount == 0) return@f false
                if (filterMyanimelist == STATE_EXCLUDE && trackCount > 0) return@f false
            }
            // Filter when there are no downloads.
            if (filterDownloaded != STATE_IGNORE) {
                val isDownloaded = when {
                    item.downloadCount != -1 -> item.downloadCount > 0
                    else -> downloadManager.getDownloadCount(item.manga) > 0
                }
                return@f if (filterDownloaded == STATE_INCLUDE) isDownloaded else !isDownloaded
            }
            true
        }

        return map.mapValues { entry -> entry.value.filter(filterFn) }
    }

    /**
     * Sets downloaded chapter count to each manga.
     *
     * @param map the map of manga.
     */
    private fun setDownloadCount(map: LibraryMap) {
        for ((_, itemList) in map) {
            for (item in itemList) {
                item.downloadCount = downloadManager.getDownloadCount(item.manga)
            }
        }
    }

    /**
     * Applies library sorting to the given map of manga.
     *
     * @param map the map to sort.
     */
    private fun applySort(map: LibraryMap): LibraryMap {
        val sortingMode = preferences.librarySortingMode().getOrDefault()

        val lastReadManga by lazy {
            var counter = 0
            db.getLastReadManga().executeAsBlocking().associate { it.id!! to counter++ }
        }
        val totalChapterManga by lazy {
            var counter = 0
            db.getTotalChapterManga().executeAsBlocking().associate { it.id!! to counter++ }
        }

        val sortFn: (LibraryItem, LibraryItem) -> Int = { i1, i2 ->
            when (sortingMode) {
                LibrarySort.ALPHA -> sortAlphabetical(i1, i2)
                LibrarySort.LAST_READ -> {
                    // Get index of manga, set equal to list if size unknown.
                    val manga1LastRead = lastReadManga[i1.manga.id!!] ?: lastReadManga.size
                    val manga2LastRead = lastReadManga[i2.manga.id!!] ?: lastReadManga.size
                    val mangaCompare = manga1LastRead.compareTo(manga2LastRead)
                    if (mangaCompare == 0) sortAlphabetical(i1, i2) else mangaCompare
                }
                LibrarySort.LAST_UPDATED -> i2.manga.last_update.compareTo(i1.manga.last_update)
                LibrarySort.DATE_ADDED -> i2.manga.date_added.compareTo(i1.manga.date_added)
                LibrarySort.UNREAD -> i1.manga.unread.compareTo(i2.manga.unread)
                LibrarySort.TOTAL -> {
                    val manga1TotalChapter = totalChapterManga[i1.manga.id!!] ?: 0
                    val mange2TotalChapter = totalChapterManga[i2.manga.id!!] ?: 0
                    val mangaCompare = manga1TotalChapter.compareTo(mange2TotalChapter)
                    if (mangaCompare == 0) sortAlphabetical(i1, i2) else mangaCompare
                }

                else -> throw Exception("Unknown sorting mode")
            }
        }

        val comparator = if (preferences.librarySortingAscending().getOrDefault())
            Comparator(sortFn)
        else
            Collections.reverseOrder(sortFn)

        return map.mapValues { entry -> entry.value.sortedWith(comparator) }
    }

    fun sortAlphabetical(i1: LibraryItem, i2: LibraryItem): Int {
        return i1.manga.title.compareTo(i2.manga.title, true)
    }

    /**
     * Get the categories and all its manga from the database.
     *
     * @return an observable of the categories and its manga.
     */
    private fun getLibraryObservable(): Observable<Library> {
        return Observable.combineLatest(
            getCategoriesObservable(),
            getLibraryMangasObservable()
        ) { dbCategories, libraryManga ->
            val categories =
                if (libraryManga.containsKey(0)) arrayListOf(Category.createDefault()) + dbCategories
                else dbCategories

            this.categories = categories
            Library(categories, libraryManga)
        }
    }

    /**
     * Get the categories from the database.
     *
     * @return an observable of the categories.
     */
    private fun getCategoriesObservable(): Observable<List<Category>> {
        return db.getCategories().asRxObservable()
    }

    /**
     * Get the manga grouped by categories.
     *
     * @return an observable containing a map with the category id as key and a list of manga as the
     * value.
     */
    private fun getLibraryMangasObservable(): Observable<LibraryMap> {
        val libraryAsList = preferences.libraryAsList()
        return db.getLibraryMangas().asRxObservable()
            .map { list ->
                list.map { LibraryItem(it, libraryAsList) }.groupBy { it.manga.category }
            }
    }

    /**
     * Requests the library to be filtered.
     */
    fun requestFilterUpdate() {
        filterTriggerRelay.call(Unit)
    }

    /**
     * Requests the library to be sorted.
     */
    fun requestSortUpdate() {
        sortTriggerRelay.call(Unit)
    }

    /**
     * Called when a manga is opened.
     */
    fun onOpenManga() {
        // Avoid further db updates for the library when it's not needed
        librarySubscription?.let { remove(it) }
    }

    /**
     * Returns the common categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    fun getCommonCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        return mangas.toSet()
            .map { db.getCategoriesForManga(it).executeAsBlocking() }
            .reduce { set1: Iterable<Category>, set2 -> set1.intersect(set2).toMutableList() }
    }

    /**
     * Remove the selected manga from the library.
     *
     * @param mangas the list of manga to delete.
     * @param deleteChapters whether to also delete downloaded chapters.
     */
    fun removeMangaFromLibrary(mangas: List<Manga>, deleteChapters: Boolean) {
        // Create a set of the list
        val mangaToDelete = mangas.distinctBy { it.id }
        mangaToDelete.forEach { it.favorite = false }

        Observable.fromCallable { db.insertMangas(mangaToDelete).executeAsBlocking() }
            .onErrorResumeNext { Observable.empty() }
            .subscribeOn(Schedulers.io())
            .subscribe()

        Observable.fromCallable {
            mangaToDelete.forEach { manga ->
                coverCache.deleteFromCache(manga.thumbnail_url)
                if (deleteChapters) {
                    val source = sourceManager.get(manga.source) as? HttpSource
                    if (source != null) {
                        downloadManager.deleteManga(manga, source)
                    }
                }
            }
        }
            .subscribeOn(Schedulers.io())
            .subscribe()
    }

    /**
     * Move the given list of manga to categories.
     *
     * @param categories the selected categories.
     * @param mangas the list of manga to move.
     */
    fun moveMangasToCategories(categories: List<Category>, mangas: List<Manga>) {
        val mc = ArrayList<MangaCategory>()

        for (manga in mangas) {
            for (cat in categories) {
                mc.add(MangaCategory.create(manga, cat))
            }
        }

        db.setMangaCategories(mc, mangas)
    }

    fun syncMangaToDex(mangaList: List<Manga>) {
        GlobalScope.launch {
            withContext(Dispatchers.IO) {
                mangaList.forEach {
                    sourceManager.getMangadex().updateFollowStatus(MdUtil.getMangaId(it.url), FollowStatus.READING)
                }
            }
        }
    }
}
