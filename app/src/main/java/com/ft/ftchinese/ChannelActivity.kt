package com.ft.ftchinese

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.widget.SwipeRefreshLayout
import android.view.View
import com.ft.ftchinese.models.*
import com.ft.ftchinese.util.Store
import com.ft.ftchinese.util.gson
import com.ft.ftchinese.util.isActiveNetworkWifi
import com.ft.ftchinese.util.isNetworkConnected
import com.koushikdutta.ion.Ion
import kotlinx.android.synthetic.main.activity_chanel.*
import kotlinx.android.synthetic.main.progress_bar.*
import kotlinx.android.synthetic.main.simple_toolbar.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info
import org.jetbrains.anko.toast

/**
 * This is used to show a channel page, which consists of a list of article summaries.
 * It is similar to `MainActivity` execpt that it does not wrap a TabLayout.
 * Implements JSInterface.OnEventListener to handle events
 * in a web page.
 */
class ChannelActivity : AppCompatActivity(),
        WVClient.OnClickListener,
        JSInterface.OnJSInteractionListener,
        SwipeRefreshLayout.OnRefreshListener,
        AnkoLogger {

    // Passed from caller
    private var mPageMeta: PagerTab? = null

    private var mLoadJob: Job? = null
    private var mRefreshJob: Job? = null

    private var mSession: SessionManager? = null

    // Content in raw/list.html
    private var mTemplate: String? = null

    private var isInProgress: Boolean = false
        set(value) {
            if (value) {
                progress_bar.visibility = View.VISIBLE
            } else {
                progress_bar.visibility = View.GONE
                swipe_refresh.isRefreshing = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chanel)

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }

        mSession = SessionManager.getInstance(this)
        /**
         * Get the metadata for this page of article list
         */
        val data = intent.getStringExtra(EXTRA_LIST_PAGE_META)

        try {
            val pageMeta = gson.fromJson<PagerTab>(data, PagerTab::class.java)

            /**
             * Set toolbar's title so that reader know where he is now.
             */
            toolbar.title = pageMeta.title

            val jsInterface = JSInterface(this)
            jsInterface.mSession = mSession

            val wvClient = WVClient(this)
            wvClient.mSession = mSession
            wvClient.setOnClickListener(this)

            web_view.apply {
                addJavascriptInterface(
                        // Set JS event listener to current class.
                        jsInterface,
                        JS_INTERFACE_NAME
                )

                webViewClient = wvClient

                webChromeClient = ChromeClient()
            }

            mPageMeta = pageMeta
            info("Initiating current page with data: $mPageMeta")

            loadContent()

        } catch (e: Exception) {
            info("$e")

            return
        }
    }

    private fun loadContent() {
        when (mPageMeta?.htmlType) {
            PagerTab.HTML_TYPE_FRAGMENT -> {
                info("loadContent: html fragment")

                mLoadJob = GlobalScope.launch(Dispatchers.Main) {
                    val cachedFrag = Store.load(this@ChannelActivity, mPageMeta?.fileName)

                    if (cachedFrag != null) {
                        loadFromCache(cachedFrag)

                        return@launch
                    }

                    loadFromServer()
                }
            }

            PagerTab.HTML_TYPE_COMPLETE -> {
                web_view.loadUrl(mPageMeta?.contentUrl)
            }
        }
    }

    private suspend  fun loadFromCache(htmlFrag: String) {
        if (mTemplate == null) {
            mTemplate = Store.readChannelTemplate(resources)
        }

        renderAndLoad(htmlFrag)

        if (!isActiveNetworkWifi()) {
            return
        }

        info("Loaded data from cache. Network on wifi and update cache in background")

        val url = mPageMeta?.contentUrl ?: return

        // Launch in background.
        mLoadJob = GlobalScope.launch {
            try {
                val frag = Ion.with(this@ChannelActivity)
                        .load(url)
                        .asString()
                        .get()
                Store.save(this@ChannelActivity, mPageMeta?.fileName, frag)
            } catch (e: Exception) {
                info("Error fetch data. Reason: $e")
            }
        }
    }

    private suspend fun loadFromServer() {
        if (!isNetworkConnected()) {
            toast(R.string.prompt_no_network)

            return
        }

        info("Cache not found. Fetch from remote ${mPageMeta?.contentUrl}")

        val url = mPageMeta?.contentUrl ?: return

        if (mTemplate == null) {
            mTemplate = Store.readChannelTemplate(resources)
        }

        if (!swipe_refresh.isRefreshing) {
            isInProgress = true
        }

        Ion.with(this)
                .load(url)
                .asString()
                .setCallback { e, result ->
                    isInProgress = false

                    if (e != null) {
                        info("Failed to fetch $url. Reason $e")
                        return@setCallback
                    }

                    renderAndLoad(result)

                    cacheData(result)
                }
    }

    private fun cacheData(data: String) {
        GlobalScope.launch {
            info("Caching data to file: ${mPageMeta?.fileName}")

            Store.save(this@ChannelActivity, mPageMeta?.fileName, data)
        }

    }
    private fun renderAndLoad(htmlFragment: String) {
        val dataToLoad = mPageMeta?.render(mTemplate, htmlFragment)

        web_view.loadDataWithBaseURL(WEBVIEV_BASE_URL, dataToLoad, "text/html", null, null)
    }

    override fun onRefresh() {
        toast(R.string.prompt_refreshing)

        if (!isNetworkConnected()) {
            toast(R.string.prompt_no_network)
            return
        }

        if (mLoadJob?.isActive == true) {
            mLoadJob?.cancel()
        }

        mRefreshJob = GlobalScope.launch(Dispatchers.Main) {
            loadFromServer()
        }
    }

    override fun onStop() {
        super.onStop()
        mLoadJob?.cancel()
        mRefreshJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        mLoadJob?.cancel()
        mRefreshJob?.cancel()

        mLoadJob = null
        mRefreshJob = null
    }

    /**
     * Launch this activity with intent
     */
    companion object {
        private const val WEBVIEV_BASE_URL = "http://www.ftchinese.com"
        private const val EXTRA_LIST_PAGE_META = "extra_list_page_metadata"

        fun start(context: Context?, page: PagerTab) {
            val intent = Intent(context, ChannelActivity::class.java).apply {
                putExtra(EXTRA_LIST_PAGE_META, gson.toJson(page))
            }

            context?.startActivity(intent)
        }
    }

    override fun onPagination(pageKey: String, pageNumber: String) {
        val pageMeta = mPageMeta ?: return

        // If ChannelActivity is started from ViewPagerFragment,
        // pageMeta.name looks like news_china_1.
        // What if it is not?
        // If ChannelActivity is started from ViewPagerFragment's pagination link,
        // the current mPageMeta.contentUrl must contain query
        // parameter like `page=2` or `p=2`. The page number
        // must not be 1 since the first page is in
        // ViewPagerFragment and is not clickable.
        // If the ChannelActivity is started from other links
        // or JSInterface, it must not contain `page=x` or `p=x`.
        val nameArr = pageMeta.name.split("_").toMutableList()
        if (nameArr.size > 0) {
            nameArr[nameArr.size - 1] = pageNumber
        }

        val newName = nameArr.joinToString("_")

        val currentUri = Uri.parse(pageMeta.contentUrl)

        if (currentUri.getQueryParameter(pageKey) != null) {

            val newUri = currentUri.buildUpon().clearQuery()

            // Replace the value of `page` or `p` with pageNumber
            for (key in currentUri.queryParameterNames) {
                if (key == pageKey) {
                    newUri.appendQueryParameter(key, pageNumber)
                }

                val value = currentUri.getQueryParameter(key)
                newUri.appendQueryParameter(key, value)
            }

            mPageMeta = PagerTab(
                    title = pageMeta.title,
                    name = newName,
                    contentUrl = newUri.build().toString(),
                    htmlType = pageMeta.htmlType
            )

            info("Loading pagination url: ${mPageMeta?.contentUrl}")

            loadContent()

        } else {
            val url = currentUri.buildUpon()
                    .appendQueryParameter(pageKey, pageNumber)
                    .build()
                    .toString()

            val listPage = PagerTab(
                    title = pageMeta.title,
                    name = "${pageMeta.name}_$pageNumber",
                    contentUrl = url,
                    htmlType = pageMeta.htmlType
            )

            ChannelActivity.start(this, listPage)
        }
    }

    override fun onPageLoaded(message: String) {
        if (BuildConfig.DEBUG) {
            val fileName = mPageMeta?.name ?: return

            GlobalScope.launch {
                Store.save(this@ChannelActivity, "$fileName.json", message)
            }
        }
    }
}
