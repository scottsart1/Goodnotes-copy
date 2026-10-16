package com.inkwell.notes.app

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.editor.EditorScreen
import com.inkwell.notes.editor.SplitScreen
import com.inkwell.notes.library.LibraryScreen
import com.inkwell.notes.library.TrashScreen
import com.inkwell.notes.search.SearchScreen
import com.inkwell.notes.settings.SettingsScreen
import com.inkwell.notes.typed.TypedDocumentScreen
import com.inkwell.notes.whiteboard.WhiteboardScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class SettingsSection(val title: String) {
    GENERAL("General"),
    INPUT("Pen & input"),
    TOOLBAR("Toolbar & view"),
    NAMING("File naming"),
    PAPER("Paper & templates"),
    RECORDING("Recording"),
    OCR("Recognition & OCR"),
    AI("AI providers"),
    USAGE("AI usage & cost"),
    STORAGE("Storage, sync & backup"),
    PRIVACY("Privacy & lock"),
    ABOUT("About"),
}

/** App-wide navigation used by every screen. */
interface AppNavigator {
    fun openLibrary(folderId: String? = null)
    /** Opens a document in the screen matching its kind. */
    fun openDocument(doc: DocumentMeta, pageIndex: Int? = null)
    fun openDocumentById(docId: String, pageIndex: Int? = null)
    /** Two documents side by side (e.g. a PDF and a notebook). */
    fun openSplit(leftDocId: String, rightDocId: String)
    fun openSearch(query: String = "")
    fun openSettings(section: SettingsSection = SettingsSection.GENERAL)
    fun openTrash()
    fun back()
}

object Routes {
    const val LIBRARY = "library?folder={folder}"
    const val NOTEBOOK = "notebook/{docId}?page={page}"
    const val WHITEBOARD = "whiteboard/{docId}"
    const val TYPED = "typed/{docId}"
    const val SPLIT = "split/{left}/{right}"
    const val SEARCH = "search?q={q}"
    const val SETTINGS = "settings?section={section}"
    const val TRASH = "trash"

    fun library(folderId: String?) = "library?folder=${Uri.encode(folderId ?: "")}"
    fun notebook(docId: String, page: Int?) = "notebook/${Uri.encode(docId)}?page=${page ?: -1}"
    fun whiteboard(docId: String) = "whiteboard/${Uri.encode(docId)}"
    fun typed(docId: String) = "typed/${Uri.encode(docId)}"
    fun split(l: String, r: String) = "split/${Uri.encode(l)}/${Uri.encode(r)}"
    fun search(q: String) = "search?q=${Uri.encode(q)}"
    fun settings(s: SettingsSection) = "settings?section=${s.name}"
}

private class NavControllerNavigator(
    private val nav: NavHostController,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) : AppNavigator {
    override fun openLibrary(folderId: String?) {
        nav.navigate(Routes.library(folderId)) { launchSingleTop = true }
    }

    override fun openDocument(doc: DocumentMeta, pageIndex: Int?) {
        val route = when (doc.kind) {
            DocKind.NOTEBOOK, DocKind.PDF -> Routes.notebook(doc.id, pageIndex)
            DocKind.WHITEBOARD -> Routes.whiteboard(doc.id)
            DocKind.TYPED -> Routes.typed(doc.id)
        }
        nav.navigate(route) { launchSingleTop = true }
    }

    override fun openDocumentById(docId: String, pageIndex: Int?) {
        scope.launch { graph.library.getDocument(docId)?.let { openDocument(it, pageIndex) } }
    }

    override fun openSplit(leftDocId: String, rightDocId: String) {
        nav.navigate(Routes.split(leftDocId, rightDocId)) { launchSingleTop = true }
    }

    override fun openSearch(query: String) {
        nav.navigate(Routes.search(query)) { launchSingleTop = true }
    }

    override fun openSettings(section: SettingsSection) {
        nav.navigate(Routes.settings(section)) { launchSingleTop = true }
    }

    override fun openTrash() {
        nav.navigate(Routes.TRASH) { launchSingleTop = true }
    }

    override fun back() {
        if (!nav.popBackStack()) nav.navigate(Routes.library(null))
    }
}

@Composable
fun InkwellNavHost(graph: AppGraph, pendingNavigation: PendingNavigation) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val navigator: AppNavigator = remember(nav) { NavControllerNavigator(nav, graph, scope) }
    pendingNavigation.attach(navigator)

    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY, arguments = listOf(navArgument("folder") { type = NavType.StringType; defaultValue = "" })) {
            LibraryScreen(navigator, it.arguments?.getString("folder")?.ifEmpty { null })
        }
        composable(
            Routes.NOTEBOOK,
            arguments = listOf(
                navArgument("docId") { type = NavType.StringType },
                navArgument("page") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) {
            val page = it.arguments?.getInt("page") ?: -1
            EditorScreen(it.arguments?.getString("docId").orEmpty(), page.takeIf { p -> p >= 0 }, navigator)
        }
        composable(Routes.WHITEBOARD, arguments = listOf(navArgument("docId") { type = NavType.StringType })) {
            WhiteboardScreen(it.arguments?.getString("docId").orEmpty(), navigator)
        }
        composable(Routes.TYPED, arguments = listOf(navArgument("docId") { type = NavType.StringType })) {
            TypedDocumentScreen(it.arguments?.getString("docId").orEmpty(), navigator)
        }
        composable(
            Routes.SPLIT,
            arguments = listOf(navArgument("left") { type = NavType.StringType }, navArgument("right") { type = NavType.StringType }),
        ) {
            SplitScreen(it.arguments?.getString("left").orEmpty(), it.arguments?.getString("right").orEmpty(), navigator)
        }
        composable(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
            SearchScreen(it.arguments?.getString("q").orEmpty(), navigator)
        }
        composable(Routes.SETTINGS, arguments = listOf(navArgument("section") { type = NavType.StringType; defaultValue = SettingsSection.GENERAL.name })) {
            val s = runCatching { SettingsSection.valueOf(it.arguments?.getString("section").orEmpty()) }.getOrDefault(SettingsSection.GENERAL)
            SettingsScreen(s, navigator)
        }
        composable(Routes.TRASH) { TrashScreen(navigator) }
    }
}

/** Navigation requested before the nav host exists (intents, notifications). */
class PendingNavigation {
    private var navigator: AppNavigator? = null
    private var pending: ((AppNavigator) -> Unit)? = null

    fun attach(n: AppNavigator) {
        navigator = n
        pending?.let { it(n); pending = null }
    }

    fun post(action: (AppNavigator) -> Unit) {
        val n = navigator
        if (n != null) action(n) else pending = action
    }
}
