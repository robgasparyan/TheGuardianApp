package com.robgas.theguardian.Fragments;


import android.arch.lifecycle.LiveData;
import android.arch.paging.DataSource;
import android.arch.paging.LivePagedListBuilder;
import android.arch.paging.PagedList;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.transition.Fade;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.robgas.theguardian.Adapters.FeedRecyclerViewAdapter;
import com.robgas.theguardian.Adapters.PinRecyclerViewAdapter;
import com.robgas.theguardian.Database.Entity.PinEntity;
import com.robgas.theguardian.MainActivity;
import com.robgas.theguardian.Models.FeedItem;
import com.robgas.theguardian.Network.FeedDataSourceFactory;
import com.robgas.theguardian.Network.RoomDataSourceFactory;
import com.robgas.theguardian.R;
import com.robgas.theguardian.SoloLearnApplication;
import com.robgas.theguardian.Utils.DetailsTransition;
import com.robgas.theguardian.Utils.SoloUtils;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class FeedFragment extends BaseFragment implements PinRecyclerViewAdapter.OnPinListItemClickListener, FeedRecyclerViewAdapter.OnItemClickListener {

    private FeedRecyclerViewAdapter mFeedAdapter;
    private PinRecyclerViewAdapter mPinAdapter;
    private Handler handler;
    private FeedDataSourceFactory feedDataFactory;
    private TextView toolbarText;
    private int delayMillis = 30000;
    private Executor executor = Executors.newFixedThreadPool(5);
    private LiveData<PagedList<FeedItem>> newsFeedObservable;
    private TextView noOfflineItemsTextView;
    private RecyclerView mFeedRecyclerView;

    public static FeedFragment newInstance() {
        FeedFragment fragment = new FeedFragment();
        Bundle args = new Bundle();
        fragment.setArguments(args);
        return fragment;
    }

    public static FeedFragment newInstance(FeedItem feedItem) {
        FeedFragment fragment = new FeedFragment();
        Bundle args = new Bundle();
        args.putParcelable(MainActivity.FeedItemId, feedItem);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_feed, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        feedDataFactory = new FeedDataSourceFactory();
        PagedList.Config pagedListConfig = new PagedList.Config.Builder()
                .setEnablePlaceholders(false)
                .setInitialLoadSizeHint(30)
                .setPageSize(15)
                .build();

        newsFeedObservable = new LivePagedListBuilder(feedDataFactory, pagedListConfig)
                .setFetchExecutor(executor)
                .build();
        if (getActivity() != null)
            ((MainActivity) getActivity()).startSoloService();

        if (getArguments() != null && getArguments().getParcelable(MainActivity.FeedItemId) != null) {
            // Fragment opened from notification
            onFeedListItemClick(getArguments().getParcelable(MainActivity.FeedItemId));
        }
        //region Pin
        LinearLayoutManager mLinearLayoutManager = new LinearLayoutManager(getActivity(), LinearLayoutManager.HORIZONTAL, false);
        RecyclerView mPinRecyclerView = view.findViewById(R.id.rv_pinned_item_list);
        TextView noPinedItemsTextView = view.findViewById(R.id.noPinedItemsTextView);
        mPinRecyclerView.setLayoutManager(mLinearLayoutManager);

        toolbarText = view.findViewById(R.id.toolbarText);
        handler = new Handler();

        SoloLearnApplication.getApplicationInstance().appDb.feedDao().getFeedList().observe(this, feedEntities -> {
            if (feedEntities != null) {
                noPinedItemsTextView.setVisibility((feedEntities.size() == 0) ? View.VISIBLE : View.GONE);
                mPinRecyclerView.setVisibility((feedEntities.size() != 0) ? View.VISIBLE : View.GONE);
                mPinAdapter = new PinRecyclerViewAdapter();
                mPinRecyclerView.setAdapter(mPinAdapter);
                mPinAdapter.setData(SoloUtils.getFeedItemFromFeedEntity(feedEntities));
                mPinAdapter.setItemClickListener(FeedFragment.this);
            }
        });

        //endregion

        SwipeRefreshLayout swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            feedDataFactory.invalidate();
            swipeRefreshLayout.setRefreshing(false);
        });

        //region Feed
        mFeedAdapter = new FeedRecyclerViewAdapter(FeedItem.DIFF_CALLBACK);
        mFeedAdapter.setFeedListItemClickListener(this);
        noOfflineItemsTextView = view.findViewById(R.id.noOfflineItemsTextView);
        mFeedRecyclerView = view.findViewById(R.id.rv_feed_list);
        mFeedRecyclerView.setAdapter(mFeedAdapter);
        //endregion

        mFeedRecyclerView.setLayoutManager(new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL));

        ImageView changeToLinearLayoutManager = view.findViewById(R.id.verticalImageView);
        changeToLinearLayoutManager.setOnClickListener(v -> {
            mFeedRecyclerView.setAdapter(null);
            mFeedRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
            mFeedAdapter.setStyle(FeedRecyclerViewAdapter.Style.LINEAR);
            mFeedRecyclerView.setAdapter(mFeedAdapter);

        });
        ImageView changeToStaggeredLayoutManager = view.findViewById(R.id.staggeredImageView);
        changeToStaggeredLayoutManager.setOnClickListener(v -> {
            mFeedRecyclerView.setAdapter(null);
            mFeedRecyclerView.setLayoutManager(new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL));
            mFeedAdapter.setStyle(FeedRecyclerViewAdapter.Style.STAGGERED);
            mFeedRecyclerView.setAdapter(mFeedAdapter);
        });

        if (isNetworkConnected()) {
            online();
        } else {
            offline();
        }

    }

    public void scheduleGetFeed() {
        handler.postDelayed(new Runnable() {
            public void run() {
                feedDataFactory.invalidate();
                handler.postDelayed(this, delayMillis);
            }
        }, delayMillis);
    }

    @Override
    public void onNetworkStateChanged(boolean connected) {
        if (getActivity() != null)
            getActivity().runOnUiThread(() -> {
                if (connected) {
                    online();
                } else {
                    offline();
                }
            });
    }

    @Override
    public void onFeedListItemClick(FeedItem item) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(DetailsFragment.EXTRA_FEED_ITEM, item);
        if (getActivity() != null) {
            DetailsFragment fragment = DetailsFragment.newInstance(bundle);
            fragment.setSharedElementEnterTransition(new DetailsTransition());
            fragment.setEnterTransition(new Fade());
            setExitTransition(new Fade());
            fragment.setSharedElementReturnTransition(new DetailsTransition());

            getActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
//                    .addSharedElement(holder.image, "1111")
                    .commit();

        }

    }

    @Override
    public void onPinListItemClick(PinRecyclerViewAdapter.PinViewHolder holder, FeedItem item) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(DetailsFragment.EXTRA_FEED_ITEM, item);
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, DetailsFragment.newInstance(bundle))
                    .addToBackStack(null)
                    .commitAllowingStateLoss();
        }
    }

    private void online() {
        noOfflineItemsTextView.setVisibility(View.GONE);
        mFeedRecyclerView.setVisibility(View.VISIBLE);
        newsFeedObservable.removeObservers(FeedFragment.this);
        toolbarText.setText(getString(R.string.sololearn));
        scheduleGetFeed();
        newsFeedObservable.observe(FeedFragment.this, feedItems -> mFeedAdapter.submitList(feedItems));
    }

    private void offline() {
        newsFeedObservable.removeObservers(FeedFragment.this);
        toolbarText.setText(getString(R.string.offline_mode));
        DataSource.Factory<Integer, FeedItem> factory = new RoomDataSourceFactory();
        LivePagedListBuilder<Integer, FeedItem> pagedListBuilder = new LivePagedListBuilder<>(factory,
                50);
        pagedListBuilder.build().observe(FeedFragment.this, offlineEntities1 -> {
            if (offlineEntities1 != null) {
                noOfflineItemsTextView.setVisibility((offlineEntities1.size() == 0) ? View.VISIBLE : View.GONE);
                mFeedRecyclerView.setVisibility((offlineEntities1.size() != 0) ? View.VISIBLE : View.GONE);
                mFeedAdapter.submitList(offlineEntities1);
            }
        });

        List<PinEntity> pinEntities = SoloLearnApplication.getApplicationInstance().appDb.feedDao().getPinList();
        if (pinEntities != null && pinEntities.size() > 0) {
            mPinAdapter = new PinRecyclerViewAdapter();
            mPinAdapter.setData(SoloUtils.getFeedItemFromFeedEntity(pinEntities));
        }
    }

}
