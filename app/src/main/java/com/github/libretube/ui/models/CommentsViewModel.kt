package com.github.libretube.ui.models

import android.content.Context
import android.util.Log
import androidx.core.text.parseAsHtml
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.liveData
import androidx.paging.map
import com.github.libretube.api.obj.Comment
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.ui.models.sources.CommentPagingSource
import com.github.libretube.ui.models.sources.CommentRepliesPagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.schabi.newpipe.extractor.timeago.patterns.pa

class CommentsViewModel : ViewModel() {

    private var lastOpenedCommentRepliesId: String? = null
    val videoIdLiveData = MutableLiveData<String>()

    val commentMap: HashMap<String, Comment> = HashMap<String, Comment>()

    val commentsLiveDataFlow = videoIdLiveData.switchMap {
        Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
            CommentPagingSource(it) {
                _commentCountLiveData.updateIfChanged(it)
            }
        }.liveData
    }
        .cachedIn(viewModelScope).asFlow()
        .map { pagingData ->
            pagingData.map { comment ->
                // update comment map when collected, this is repo within view model scoped
                commentMap[comment.commentId] = comment
                comment
            }
        }

    fun getRepliesFlow(
        commentId: String
    ): Flow<PagingData<Comment>> {
        val originalComment = commentMap.get(commentId) ?: return emptyFlow()
        return videoIdLiveData.switchMap { videoId ->
            Pager(PagingConfig(20, enablePlaceholders = false)) {
                CommentRepliesPagingSource(videoId, originalComment)
            } .liveData
        }.cachedIn(viewModelScope).asFlow()
    }

    val commentsLiveData = videoIdLiveData.switchMap {
        Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
            CommentPagingSource(it) {
                _commentCountLiveData.updateIfChanged(it)
            }
        }.liveData
    }
        .cachedIn(viewModelScope)

    private val _commentCountLiveData = MutableLiveData<Long>()
    val commentCountLiveData: LiveData<Long> = _commentCountLiveData

    private val _currentCommentsPosition = MutableLiveData(0)
    val currentCommentsPosition: LiveData<Int> = _currentCommentsPosition

    private val _currentRepliesPosition = MutableLiveData(0)
    val currentRepliesPosition: LiveData<Int> = _currentRepliesPosition

    fun reset() {
        _currentCommentsPosition.value = 0
    }

    fun setCommentsPosition(position: Int) {
        if (position != currentCommentsPosition.value) {
            _currentCommentsPosition.value = position
        }
    }

    fun setRepliesPosition(position: Int) {
        if (position != currentRepliesPosition.value) {
            _currentRepliesPosition.value = position
        }
    }

    fun setLastOpenedCommentRepliesId(id: String) {
        if (lastOpenedCommentRepliesId != id) {
            _currentRepliesPosition.value = 0
            lastOpenedCommentRepliesId = id
        }
    }

    fun saveToClipboard(context: Context, comment: Comment) {
        ClipboardHelper.save(
            context,
            text = comment.commentText.orEmpty().parseAsHtml().toString(),
            notify = true
        )
    }
}
