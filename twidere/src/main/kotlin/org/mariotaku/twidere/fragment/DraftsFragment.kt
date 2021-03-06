/*
 * 				Twidere - Twitter client for Android
 * 
 *  Copyright (C) 2012-2014 Mariotaku Lee <mariotaku.lee@gmail.com>
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.fragment

import android.app.AlertDialog
import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnClickListener
import android.content.Intent
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.support.v4.app.DialogFragment
import android.support.v4.app.FragmentActivity
import android.support.v4.app.LoaderManager.LoaderCallbacks
import android.support.v4.content.CursorLoader
import android.support.v4.content.Loader
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.widget.AbsListView.MultiChoiceModeListener
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ListView
import android.widget.Toast
import kotlinx.android.synthetic.main.fragment_drafts.*
import nl.komponents.kovenant.task
import nl.komponents.kovenant.ui.successUi
import org.mariotaku.sqliteqb.library.Columns.Column
import org.mariotaku.sqliteqb.library.Expression
import org.mariotaku.sqliteqb.library.RawItemArray
import org.mariotaku.twidere.R
import org.mariotaku.twidere.TwidereConstants.*
import org.mariotaku.twidere.activity.iface.IExtendedActivity
import org.mariotaku.twidere.adapter.DraftsAdapter
import org.mariotaku.twidere.constant.IntentConstants
import org.mariotaku.twidere.extension.writeMimeMessageTo
import org.mariotaku.twidere.model.Draft
import org.mariotaku.twidere.model.ParcelableMediaUpdate
import org.mariotaku.twidere.model.draft.SendDirectMessageActionExtras
import org.mariotaku.twidere.model.util.ParcelableStatusUpdateUtils
import org.mariotaku.twidere.provider.TwidereDataStore.Drafts
import org.mariotaku.twidere.service.BackgroundOperationService
import org.mariotaku.twidere.util.AsyncTaskUtils
import org.mariotaku.twidere.util.JsonSerializer
import org.mariotaku.twidere.util.Utils.getDefaultTextSize
import java.io.File
import java.io.FileOutputStream
import java.util.*

class DraftsFragment : BaseSupportFragment(), LoaderCallbacks<Cursor?>, OnItemClickListener, MultiChoiceModeListener {

    private lateinit var adapter: DraftsAdapter

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.action_multi_select_drafts, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        updateTitle(mode)
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.delete -> {
                val f = DeleteDraftsConfirmDialogFragment()
                val args = Bundle()
                args.putLongArray(IntentConstants.EXTRA_IDS, listView.checkedItemIds)
                f.arguments = args
                f.show(childFragmentManager, "delete_drafts_confirm")
            }
            R.id.send -> {
                val checked = listView.checkedItemPositions
                val list = ArrayList<Draft>()
                for (i in 0 until checked.size()) {
                    val position = checked.keyAt(i)
                    if (checked.valueAt(i)) {
                        list.add(adapter.getDraft(position))
                    }
                }
                if (sendDrafts(list)) {
                    val where = Expression.`in`(Column(Drafts._ID),
                            RawItemArray(listView.checkedItemIds))
                    context.contentResolver.delete(Drafts.CONTENT_URI, where.sql, null)
                }
            }
            R.id.save -> {
                val checked = listView.checkedItemPositions
                val drafts = ArrayList<Draft>()
                for (i in 0 until checked.size()) {
                    val position = checked.keyAt(i)
                    if (checked.valueAt(i)) {
                        drafts.add(adapter.getDraft(position))
                    }
                }
                task {
                    val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    val paths = drafts.map { draft ->
                        val name = "${draft.timestamp}.eml"
                        val destFile = File(pubDir, name)
                        FileOutputStream(destFile).use {
                            draft.writeMimeMessageTo(context, it)
                            it.flush()
                        }
                        return@map destFile.absolutePath
                    }
                    MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
                }.successUi {
                    Toast.makeText(context, R.string.draft_saved, Toast.LENGTH_SHORT).show()
                }.fail { ex ->
                    Log.w(LOGTAG, ex)
                }
            }
            else -> {
                return false
            }
        }
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {

    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor?> {
        val uri = Drafts.CONTENT_URI_UNSENT
        val cols = Drafts.COLUMNS
        val orderBy = Drafts.TIMESTAMP + " DESC"
        return CursorLoader(activity, uri, cols, null, null, orderBy)
    }

    override fun onLoadFinished(loader: Loader<Cursor?>, cursor: Cursor?) {
        adapter.swapCursor(cursor)
        setListShown(true)
    }

    override fun onLoaderReset(loader: Loader<Cursor?>) {
        adapter.swapCursor(null)
    }

    override fun onItemCheckedStateChanged(mode: ActionMode, position: Int, id: Long,
                                           checked: Boolean) {
        updateTitle(mode)
    }

    override fun onItemClick(view: AdapterView<*>, child: View, position: Int, id: Long) {
        val item = adapter.getDraft(position)
        if (TextUtils.isEmpty(item.action_type)) {
            editDraft(item)
            return
        }
        when (item.action_type) {
            "0", "1", Draft.Action.UPDATE_STATUS, Draft.Action.REPLY, Draft.Action.QUOTE -> {
                editDraft(item)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_drafts, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        adapter = DraftsAdapter(activity).apply {
            textSize = preferences.getInt(KEY_TEXT_SIZE, getDefaultTextSize(activity)).toFloat()
        }

        listView.adapter = adapter
        listView.emptyView = emptyView
        listView.onItemClickListener = this
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL
        listView.setMultiChoiceModeListener(this)
        emptyIcon.setImageResource(R.drawable.ic_info_draft)
        emptyText.setText(R.string.drafts_hint_messages)
        loaderManager.initLoader(0, null, this)
        setListShown(false)
    }

    override fun onStart() {
        twitterWrapper.clearNotificationAsync(NOTIFICATION_ID_DRAFTS)
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    fun setListShown(listShown: Boolean) {
        listContainer.visibility = if (listShown) View.VISIBLE else View.GONE
        progressContainer.visibility = if (listShown) View.GONE else View.VISIBLE
        emptyView.visibility = if (listShown && adapter.isEmpty) View.VISIBLE else View.GONE
    }

    private fun editDraft(draft: Draft) {
        val intent = Intent(INTENT_ACTION_EDIT_DRAFT)
        intent.putExtra(EXTRA_DRAFT, draft)
        context.contentResolver.delete(Drafts.CONTENT_URI, Expression.equals(Drafts._ID, draft._id).sql, null)
        startActivityForResult(intent, REQUEST_COMPOSE)
    }

    private fun sendDrafts(list: List<Draft>): Boolean {
        loop@ for (item in list) {
            if (TextUtils.isEmpty(item.action_type)) {
                item.action_type = Draft.Action.UPDATE_STATUS
            }
            when (item.action_type) {
                Draft.Action.UPDATE_STATUS_COMPAT_1, Draft.Action.UPDATE_STATUS_COMPAT_2, Draft.Action.UPDATE_STATUS, Draft.Action.REPLY, Draft.Action.QUOTE -> {
                    BackgroundOperationService.updateStatusesAsync(context, item.action_type,
                            ParcelableStatusUpdateUtils.fromDraftItem(activity, item))
                }
                Draft.Action.SEND_DIRECT_MESSAGE_COMPAT, Draft.Action.SEND_DIRECT_MESSAGE -> {
                    var recipientId: String? = null
                    if (item.action_extras is SendDirectMessageActionExtras) {
                        recipientId = (item.action_extras as SendDirectMessageActionExtras).recipientId
                    }
                    if (item.account_keys?.isEmpty() ?: true || recipientId == null) {
                        continue@loop
                    }
                    val accountId = item.account_keys?.firstOrNull()
                    val imageUri = item.media?.firstOrNull()?.uri
                    twitterWrapper.sendDirectMessageAsync(accountId, recipientId, item.text, imageUri)
                }
            }
        }
        return true
    }

    private fun updateTitle(mode: ActionMode?) {
        if (listView == null || mode == null) return
        val count = listView.checkedItemCount
        mode.title = resources.getQuantityString(R.plurals.Nitems_selected, count, count)
    }

    class DeleteDraftsConfirmDialogFragment : BaseDialogFragment(), OnClickListener {

        override fun onClick(dialog: DialogInterface, which: Int) {
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> {
                    val args = arguments ?: return
                    AsyncTaskUtils.executeTask(DeleteDraftsTask(activity, args.getLongArray(IntentConstants.EXTRA_IDS)))
                }
            }
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val context = activity
            val builder = AlertDialog.Builder(context)
            builder.setMessage(R.string.delete_drafts_confirm)
            builder.setPositiveButton(android.R.string.ok, this)
            builder.setNegativeButton(android.R.string.cancel, null)
            return builder.create()
        }

    }

    private class DeleteDraftsTask(
            private val activity: FragmentActivity,
            private val ids: LongArray
    ) : AsyncTask<Any, Any, Int>() {
        private val notificationManager: NotificationManager
        private val handler: Handler

        init {
            notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            handler = Handler(activity.mainLooper)
        }

        override fun doInBackground(vararg params: Any): Int? {
            val resolver = activity.contentResolver
            val where = Expression.`in`(Column(Drafts._ID), RawItemArray(ids))
            val projection = arrayOf(Drafts.MEDIA)
            val c = resolver.query(Drafts.CONTENT_URI, projection, where.sql, null, null) ?: return 0
            val idxMedia = c.getColumnIndex(Drafts.MEDIA)
            c.moveToFirst()
            while (!c.isAfterLast) {
                val mediaArray = JsonSerializer.parseArray(c.getString(idxMedia), ParcelableMediaUpdate::class.java)
                if (mediaArray != null) {
                    for (media in mediaArray) {
                        val uri = Uri.parse(media.uri)
                        if ("file" == uri.scheme) {
                            val file = File(uri.path)
                            if (!file.delete()) {
                                Log.w(LOGTAG, String.format("Unable to delete %s", file))
                            }
                        }
                    }
                }
                c.moveToNext()
            }
            c.close()
            return resolver.delete(Drafts.CONTENT_URI, where.sql, null)
        }

        override fun onPreExecute() {
            super.onPreExecute()
            (activity as IExtendedActivity).executeAfterFragmentResumed {
                val activity = it as FragmentActivity
                val f = ProgressDialogFragment.show(activity.supportFragmentManager, FRAGMENT_TAG_DELETING_DRAFTS)
                f.isCancelable = false
            }
        }

        override fun onPostExecute(result: Int?) {
            super.onPostExecute(result)
            (activity as IExtendedActivity).executeAfterFragmentResumed {
                val activity = it as FragmentActivity
                val fm = activity.supportFragmentManager
                val f = fm.findFragmentByTag(FRAGMENT_TAG_DELETING_DRAFTS)
                if (f is DialogFragment) {
                    f.dismiss()
                }
            }
            ids.forEach { id ->
                val tag = Uri.withAppendedPath(Drafts.CONTENT_URI, id.toString()).toString()
                notificationManager.cancel(tag, NOTIFICATION_ID_DRAFTS)
            }
        }

        companion object {

            private val FRAGMENT_TAG_DELETING_DRAFTS = "deleting_drafts"
        }
    }
}
