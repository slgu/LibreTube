package com.github.libretube.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.fragment.app.setFragmentResult
import androidx.navigation.NavController
import androidx.navigation.NavHost
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.Comment
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentCommentsBinding
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.ui.adapters.CommentsPagingAdapter
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.sheets.CommentsSheet
import com.github.libretube.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.timeago.patterns.ca
import org.schabi.newpipe.extractor.timeago.patterns.id
import org.schabi.newpipe.extractor.timeago.patterns.pa

private const val TAG = "CommentsMainFragment"

@Serializable
sealed class Screen(val route: String) {

    @Serializable
    object Comments : Screen("comments")

    @Serializable
    class CommentDetail(val commentId: String) : Screen("comments_details")
}

class CommentsMainFragment : Fragment(R.layout.fragment_comments) {

    private val viewModel: CommentsViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = FragmentCommentsBinding.bind(view)

        val composeView = binding.commentsRV1
        val nativeView = binding.commentsRV2

        val enableComposeMigration =
            PreferenceHelper.getBoolean(PreferenceKeys.ENABLE_COMPOSE_MIGRATION, false)

        Log.d(TAG, "compose migration enabled ${enableComposeMigration}")
        if (enableComposeMigration) {
            composeView.visibility = View.VISIBLE
            nativeView.visibility = View.GONE
            composeView.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            var scrollController: ScrollController? = null
            composeView.setContent {
                CommentsNavHost(viewModel) {
                    scrollController = it
                }
            }
            val commentsSheet = parentFragment as? CommentsSheet
            commentsSheet?.updateFragmentInfo(false, getString(R.string.comments))
            viewModel.commentCountLiveData.observe(viewLifecycleOwner) { commentCount ->
                if (commentCount == null) return@observe

                commentsSheet?.updateFragmentInfo(
                    false,
                    getString(R.string.comments_count, commentCount.formatShort())
                )
            }
            commentsSheet?.binding?.btnScrollToTop?.setOnClickListener {
                scrollController?.scrollToTop()
            }
            commentsSheet?.binding?.btnScrollToTop?.isVisible = true
        } else {
            nativeView.visibility = View.VISIBLE
            composeView.visibility = View.GONE
            setUpNativeView(binding, binding.commentsRV2)
        }
    }

    companion object {
        private const val POSITION_START = 0
    }

    fun setUpNativeView(binding: FragmentCommentsBinding, nativeView: RecyclerView) {
        val layoutManager = LinearLayoutManager(requireContext())
        nativeView.layoutManager = layoutManager
        val commentsSheet = parentFragment as? CommentsSheet
        commentsSheet?.binding?.btnScrollToTop?.setOnClickListener {
            // scroll back to the top / first comment
            layoutManager.startSmoothScroll(LinearSmoothScroller(view!!.context).also {
                it.targetPosition = POSITION_START
            })
            viewModel.setCommentsPosition(POSITION_START)
        }

        nativeView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return

                val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
                viewModel.setCommentsPosition(firstVisiblePosition)
            }
        })

        commentsSheet?.updateFragmentInfo(false, getString(R.string.comments))

        val commentPagingAdapter = CommentsPagingAdapter(
            false,
            requireArguments().getString(IntentData.channelAvatar),
            handleLink = {
                setFragmentResult(
                    CommentsSheet.HANDLE_LINK_REQUEST_KEY,
                    bundleOf(IntentData.url to it),
                )
            },
            saveToClipboard = { comment ->
                viewModel.saveToClipboard(view!!.context, comment)
            },
            navigateToChannel = { comment ->
                NavigationHelper.navigateChannel(view!!.context, comment.commentorUrl)
                setFragmentResult(CommentsSheet.DISMISS_SHEET_REQUEST_KEY, Bundle.EMPTY)
            },
            navigateToReplies = { comment, channelAvatar ->
                if (comment.repliesPage != null) {
                    val args = bundleOf(
                        IntentData.videoId to viewModel.videoIdLiveData.value,
                        IntentData.comment to comment,
                        IntentData.channelAvatar to channelAvatar
                    )
                    parentFragmentManager.commit {
                        viewModel.setLastOpenedCommentRepliesId(comment.commentId)
                        replace<CommentsRepliesFragment>(R.id.commentFragContainer, args = args)
                        addToBackStack(null)
                    }
                }
            },
        )

        nativeView.adapter = commentPagingAdapter

        commentPagingAdapter.addLoadStateListener { loadStates ->
            binding.progress.isVisible = loadStates.refresh is LoadState.Loading

            val refreshState = loadStates.source.refresh
            if (refreshState is LoadState.NotLoading && commentPagingAdapter.itemCount > 0) {
                viewModel.currentCommentsPosition.value?.let { position ->
                    nativeView.scrollToPosition(maxOf(position, POSITION_START))
                }
            }

            if (loadStates.append is LoadState.NotLoading && loadStates.append.endOfPaginationReached && commentPagingAdapter.itemCount == 0) {
                binding.errorTV.text = getString(R.string.no_comments_available)
                binding.errorTV.isVisible = true
            }
        }

        viewModel.currentCommentsPosition.observe(viewLifecycleOwner) {
            // hide or show the scroll to top button
            commentsSheet?.binding?.btnScrollToTop?.isVisible = it != 0
        }

        viewModel.commentsLiveData.observe(viewLifecycleOwner) {
            commentPagingAdapter.submitData(lifecycle, it)
        }

        viewModel.commentCountLiveData.observe(viewLifecycleOwner) { commentCount ->
            if (commentCount == null) return@observe
            commentsSheet?.updateFragmentInfo(
                false,
                getString(R.string.comments_count, commentCount.formatShort())
            )
        }
    }
}


@Composable
fun CommentsNavHost(
    viewModel: CommentsViewModel,
    scrollControlHolder: (scrollController: ScrollController) -> Unit
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Screen.Comments,
    ) {

        composable<Screen.Comments> {
            Comments(navController, viewModel, scrollControlHolder)
        }

        composable<Screen.CommentDetail>{ backStackEntry ->
            val commentDetail = backStackEntry.toRoute<Screen.CommentDetail>()
            val pagingData = remember(commentDetail.commentId) {
                viewModel.getRepliesFlow(commentDetail.commentId)
            }
            Replies(
                pagingData,
            )
        }
    }
}


@Composable
fun Comments(
    navController: NavController,
    viewModel: CommentsViewModel,
    scrollControlHolder: (scrollController: ScrollController) -> Unit
) {
    val state = viewModel.commentsLiveDataFlow.collectAsLazyPagingItems()
    val loadState = state.loadState
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val controller = remember {
        ScrollControllerImpl(scope, listState)
    }
    LaunchedEffect(Unit) {
        scrollControlHolder(controller)
    }
    LazyColumn(state = listState) {
        items(state.itemCount) { idx ->
            commentCard(state[idx]!!, onClickHandler = { comment ->
                navController.navigate(Screen.CommentDetail(comment.commentId))
            })
        }
    }
    if (loadState.append is LoadState.NotLoading && loadState.append.endOfPaginationReached && state.itemCount == 0) {
        val noComment = stringResource(R.string.no_comments_available)
        Text(
            text = noComment,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

interface ScrollController {
    fun scrollToTop()
}

class ScrollControllerImpl(
    private val scope: CoroutineScope,
    private val listState: LazyListState,
) : ScrollController {

    override fun scrollToTop() {
        scope.launch {
            listState.animateScrollToItem(0)
        }
    }
}


@Composable
fun Replies(
    repliesFlow: Flow<PagingData<Comment>>,
) {
    val state = repliesFlow.collectAsLazyPagingItems()
    val isReady =
        state.loadState.refresh is LoadState.NotLoading
                && state.itemCount > 0

    // some debug logs
    SideEffect { Log.d("xxxx", "recompose") }
    LaunchedEffect(Unit) {
        snapshotFlow { state.loadState }
            .collect { loadState ->
                // no-op
                Log.d("xxxx", "refresh state ${state.loadState.refresh} cnt: ${state.itemCount}")
                Log.d("xxxx", "append state ${state.loadState.append} cnt: ${state.itemCount}")
                Log.d("xxxx", "prepend state ${state.loadState.prepend} cnt: ${state.itemCount}")
            }
    }

    if (isReady) {
        LazyColumn {
            items(
                state.itemCount,
                key = { idx ->
                    idx
                }
            ) { idx ->
                commentCard(
                    state[idx]!!, modifier =
                        if (idx != 0) {
                            Modifier.padding(start = 20.dp)
                        } else {
                            Modifier
                        }
                )
            }
        }
    } else {
        // NO-op
    }
}

@Composable
fun commentCard(
    card: Comment,
    modifier: Modifier = Modifier,
    onClickHandler: ((comment: Comment) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)) // rounded_ripple substitute
            .clickable(
                onClick = {
                    onClickHandler?.invoke(card)
                }
            )
            .padding(16.dp),
    ) {
        Row {
            // Avatar
            AsyncImage(
                model = ProxyHelper.rewriteUrlUsingProxyPreference(card.thumbnail),
                contentDescription = null,
                modifier = Modifier
                    .size(25.dp)
                    .clip(CircleShape)
                    .background(Color.Gray),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                // author info
                Row {
                    Text(card.author)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(card.commentedTimeMillis?.let {
                        TextUtils.formatRelativeDate(it).toString()
                    } ?: card.commentedTime)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    card.commentText?.replace("</a>", "</a> ")
                        ?.parseAsHtml()?.toString() ?: ""
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    IconText(drawableId = R.drawable.ic_thumb_up, card.likeCount.formatShort())
                    Spacer(modifier = Modifier.width(8.dp))
                    IconText(drawableId = R.drawable.ic_comment, card.replyCount.formatShort())
                }
            }
        }
    }
}

@Composable
fun IconText(
    drawableId: Int,
    text: String,
    iconSize: Dp = 20.dp,
    spacing: Dp = 8.dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(drawableId),
            contentDescription = null,
            modifier = Modifier.size(iconSize)
        )
        Spacer(modifier = Modifier.width(spacing))
        Text(text = text)
    }
}