package eu.kanade.tachiyomi.ui.setting

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceScreen
import com.afollestad.materialdialogs.MaterialDialog
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.GithubUpdateChecker
import eu.kanade.tachiyomi.data.updater.GithubUpdateResult
import eu.kanade.tachiyomi.data.updater.UpdaterService
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.main.ChangelogDialogController
import eu.kanade.tachiyomi.util.toast
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import timber.log.Timber

class SettingsAboutController : SettingsController() {

    /**
     * Checks for new releases
     */
    private val updateChecker by lazy { GithubUpdateChecker() }

    /**
     * The subscribtion service of the obtained release object
     */
    private var releaseSubscription: Subscription? = null

    override fun setupPreferenceScreen(screen: PreferenceScreen) = with(screen) {
        titleRes = R.string.pref_category_about

        preference {
            title = "Discord"
            val url = "https://discord.gg/tachiyomi"
            summary = url
            onClick {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
        }
        preference {
            title = "Github"
            val url = "https://github.com/CarlosEsco/Neko"
            summary = url
            onClick {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
            isIconSpaceReserved = true
        }
        preference {
            titleRes = R.string.version
            summary = BuildConfig.VERSION_NAME
            onClick { checkVersion() }

            isIconSpaceReserved = true
        }
        preference {
            titleRes = R.string.build_time
            summary = getFormattedBuildTime()

            onClick {
                ChangelogDialogController().showDialog(router)
            }
        }
    }

    override fun onDestroyView(view: View) {
        super.onDestroyView(view)
        releaseSubscription?.unsubscribe()
        releaseSubscription = null
    }

    /**
     * Checks version and shows a user prompt if an update is available.
     */
    private fun checkVersion() {
        if (activity == null) return

        activity?.toast(R.string.update_check_look_for_updates)
        releaseSubscription?.unsubscribe()
        releaseSubscription = updateChecker.checkForUpdate()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result ->
                when (result) {
                    is GithubUpdateResult.NewUpdate -> {
                        val body = result.release.changeLog
                        val url = result.release.downloadLink

                        // Create confirmation window
                        NewUpdateDialogController(body, url).showDialog(router)
                    }
                    is GithubUpdateResult.NoNewUpdate -> {
                        activity?.toast(R.string.update_check_no_new_updates)
                    }
                }
            }, { error ->
                activity?.toast(error.message)
                Timber.e(error)
            })
    }

    class NewUpdateDialogController(bundle: Bundle? = null) : DialogController(bundle) {

        constructor(body: String, url: String) : this(Bundle().apply {
            putString(BODY_KEY, body)
            putString(URL_KEY, url)
        })

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            return MaterialDialog(activity!!)
                .title(R.string.update_check_title)
                .message(text = args.getString(BODY_KEY)!!)
                .negativeButton(R.string.update_check_ignore)
                .positiveButton(R.string.update_check_confirm) {
                    val appContext = applicationContext
                    if (appContext != null) {
                        // Start download
                        val url = args.getString(URL_KEY)!!
                        UpdaterService.downloadUpdate(appContext, url)
                    }
                }
        }

        private companion object {
            const val BODY_KEY = "NewUpdateDialogController.body"
            const val URL_KEY = "NewUpdateDialogController.key"
        }
    }

    private fun getFormattedBuildTime(): String {
        try {
            val inputDf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            inputDf.timeZone = TimeZone.getTimeZone("UTC")
            val date = inputDf.parse(BuildConfig.BUILD_TIME)!!

            val outputDf = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()
            )
            outputDf.timeZone = TimeZone.getDefault()

            return outputDf.format(date)
        } catch (e: ParseException) {
            return BuildConfig.BUILD_TIME
        }
    }
}
