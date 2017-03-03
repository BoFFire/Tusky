/* Copyright 2017 Andrew Dawson
 *
 * This file is part of Tusky.
 *
 * Tusky is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky. If not, see
 * <http://www.gnu.org/licenses/>. */

package com.keylesspalace.tusky;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.design.widget.TabLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.volley.AuthFailureError;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonArrayRequest;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TimelineFragment extends SFragment implements
        SwipeRefreshLayout.OnRefreshListener, StatusActionListener, FooterActionListener {
    private static final String TAG = "Timeline"; // logging tag and Volley request tag

    public enum Kind {
        HOME,
        MENTIONS,
        PUBLIC,
        TAG,
        USER,
        FAVOURITES
    }

    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private TimelineAdapter adapter;
    private Kind kind;
    private String hashtagOrId;
    private LinearLayoutManager layoutManager;
    private EndlessOnScrollListener scrollListener;
    private TabLayout.OnTabSelectedListener onTabSelectedListener;

    public static TimelineFragment newInstance(Kind kind) {
        TimelineFragment fragment = new TimelineFragment();
        Bundle arguments = new Bundle();
        arguments.putString("kind", kind.name());
        fragment.setArguments(arguments);
        return fragment;
    }

    public static TimelineFragment newInstance(Kind kind, String hashtagOrId) {
        TimelineFragment fragment = new TimelineFragment();
        Bundle arguments = new Bundle();
        arguments.putString("kind", kind.name());
        arguments.putString("hashtag_or_id", hashtagOrId);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onDestroy() {
        VolleySingleton.getInstance(getContext()).cancelAll(TAG);
        super.onDestroy();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
             Bundle savedInstanceState) {

        Bundle arguments = getArguments();
        kind = Kind.valueOf(arguments.getString("kind"));
        if (kind == Kind.TAG || kind == Kind.USER) {
            hashtagOrId = arguments.getString("hashtag_or_id");
        }

        View rootView = inflater.inflate(R.layout.fragment_timeline, container, false);

        // Setup the SwipeRefreshLayout.
        Context context = getContext();
        swipeRefreshLayout = (SwipeRefreshLayout) rootView.findViewById(R.id.swipe_refresh_layout);
        swipeRefreshLayout.setOnRefreshListener(this);
        // Setup the RecyclerView.
        recyclerView = (RecyclerView) rootView.findViewById(R.id.recycler_view);
        recyclerView.setHasFixedSize(true);
        layoutManager = new LinearLayoutManager(context);
        recyclerView.setLayoutManager(layoutManager);
        DividerItemDecoration divider = new DividerItemDecoration(
                context, layoutManager.getOrientation());
        Drawable drawable = ThemeUtils.getDrawable(context, R.attr.status_divider_drawable,
                R.drawable.status_divider_dark);
        divider.setDrawable(drawable);
        recyclerView.addItemDecoration(divider);
        scrollListener = new EndlessOnScrollListener(layoutManager) {
            @Override
            public void onLoadMore(int page, int totalItemsCount, RecyclerView view) {
                TimelineAdapter adapter = (TimelineAdapter) view.getAdapter();
                Status status = adapter.getItem(adapter.getItemCount() - 2);
                if (status != null) {
                    sendFetchTimelineRequest(status.getId());
                } else {
                    sendFetchTimelineRequest();
                }
            }
        };
        recyclerView.addOnScrollListener(scrollListener);
        adapter = new TimelineAdapter(this, this);
        recyclerView.setAdapter(adapter);

        if (jumpToTopAllowed()) {
            TabLayout layout = (TabLayout) getActivity().findViewById(R.id.tab_layout);
            onTabSelectedListener = new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {}

                @Override
                public void onTabUnselected(TabLayout.Tab tab) {}

                @Override
                public void onTabReselected(TabLayout.Tab tab) {
                    jumpToTop();
                }
            };
            layout.addOnTabSelectedListener(onTabSelectedListener);
        }

        sendFetchTimelineRequest();

        return rootView;
    }

    @Override
    public void onDestroyView() {
        if (jumpToTopAllowed()) {
            TabLayout tabLayout = (TabLayout) getActivity().findViewById(R.id.tab_layout);
            tabLayout.removeOnTabSelectedListener(onTabSelectedListener);
        }
        super.onDestroyView();
    }

    private boolean jumpToTopAllowed() {
        return kind != Kind.TAG && kind != Kind.FAVOURITES;
    }

    private void jumpToTop() {
        layoutManager.scrollToPosition(0);
        scrollListener.reset();
    }

    private void sendFetchTimelineRequest(final String fromId) {
        String endpoint;
        switch (kind) {
            default:
            case HOME: {
                endpoint = getString(R.string.endpoint_timelines_home);
                break;
            }
            case MENTIONS: {
                endpoint = getString(R.string.endpoint_timelines_mentions);
                break;
            }
            case PUBLIC: {
                endpoint = getString(R.string.endpoint_timelines_public);
                break;
            }
            case TAG: {
                endpoint = String.format(getString(R.string.endpoint_timelines_tag), hashtagOrId);
                break;
            }
            case USER: {
                endpoint = String.format(getString(R.string.endpoint_statuses), hashtagOrId);
                break;
            }
            case FAVOURITES: {
                endpoint = getString(R.string.endpoint_favourites);
                break;
            }
        }
        String url = "https://" + domain + endpoint;
        if (fromId != null) {
            url += "?max_id=" + fromId;
        }
        JsonArrayRequest request = new JsonArrayRequest(url,
                new Response.Listener<JSONArray>() {
                    @Override
                    public void onResponse(JSONArray response) {
                        List<Status> statuses = null;
                        try {
                            statuses = Status.parse(response);
                        } catch (JSONException e) {
                            onFetchTimelineFailure(e);
                        }
                        if (statuses != null) {
                            onFetchTimelineSuccess(statuses, fromId);
                        }
                    }
                }, new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        onFetchTimelineFailure(error);
                    }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + accessToken);
                return headers;
            }
        };
        request.setTag(TAG);
        VolleySingleton.getInstance(getContext()).addToRequestQueue(request);
    }

    private void sendFetchTimelineRequest() {
        sendFetchTimelineRequest(null);
    }

    private static boolean findStatus(List<Status> statuses, String id) {
        for (Status status : statuses) {
            if (status.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public void onFetchTimelineSuccess(List<Status> statuses, String fromId) {
        if (fromId != null) {
            if (statuses.size() > 0 && !findStatus(statuses, fromId)) {
                setFetchTimelineState(FooterViewHolder.State.LOADING);
                adapter.addItems(statuses);
            } else {
                setFetchTimelineState(FooterViewHolder.State.END_OF_TIMELINE);
            }
        } else {
            adapter.update(statuses);
        }
        swipeRefreshLayout.setRefreshing(false);
    }

    public void onFetchTimelineFailure(Exception exception) {
        setFetchTimelineState(FooterViewHolder.State.RETRY);
        swipeRefreshLayout.setRefreshing(false);
        Log.e(TAG, "Fetch Failure: " + exception.getMessage());
    }

    private void setFetchTimelineState(FooterViewHolder.State state) {
        adapter.setFooterState(state);
        RecyclerView.ViewHolder viewHolder =
                recyclerView.findViewHolderForAdapterPosition(adapter.getItemCount() - 1);
        if (viewHolder != null) {
            FooterViewHolder holder = (FooterViewHolder) viewHolder;
            holder.setState(state);
        }
    }

    public void onRefresh() {
        sendFetchTimelineRequest();
    }

    public void onLoadMore() {
        Status status = adapter.getItem(adapter.getItemCount() - 2);
        if (status != null) {
            sendFetchTimelineRequest(status.getId());
        } else {
            sendFetchTimelineRequest();
        }
    }

    public void onReply(int position) {
        super.reply(adapter.getItem(position));
    }

    public void onReblog(final boolean reblog, final int position) {
        super.reblog(adapter.getItem(position), reblog, adapter, position);
    }

    public void onFavourite(final boolean favourite, final int position) {
        super.favourite(adapter.getItem(position), favourite, adapter, position);
    }

    public void onMore(View view, final int position) {
        super.more(adapter.getItem(position), view, adapter, position);
    }

    public void onViewMedia(String url, Status.MediaAttachment.Type type) {
        super.viewMedia(url, type);
    }

    public void onViewThread(int position) {
        super.viewThread(adapter.getItem(position));
    }

    public void onViewTag(String tag) {
        if (kind == Kind.TAG && hashtagOrId.equals(tag)) {
            // If already viewing a tag page, then ignore any request to view that tag again.
            return;
        }
        super.viewTag(tag);
    }

    public void onViewAccount(String id) {
        if (kind == Kind.USER && hashtagOrId.equals(id)) {
            /* If already viewing an account page, then any requests to view that account page
             * should be ignored. */
            return;
        }
        super.viewAccount(id);
    }
}