package com.webterm.feature.home;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 会话列表专用 ItemAnimator：保留短促的增删动画，禁止跨组位移与 change 动画。
 */
final class SessionListItemAnimator extends DefaultItemAnimator {

    static final long ADD_REMOVE_DURATION_MS = 140L;

    SessionListItemAnimator() {
        setSupportsChangeAnimations(false);
        setAddDuration(ADD_REMOVE_DURATION_MS);
        setRemoveDuration(ADD_REMOVE_DURATION_MS);
    }

    @Override
    public boolean animateMove(RecyclerView.ViewHolder holder,
                               int fromX, int fromY, int toX, int toY) {
        // 立即落位，避免 cwd 切换时卡片长距离缓慢漂移。
        holder.itemView.animate().cancel();
        holder.itemView.setTranslationX(0f);
        holder.itemView.setTranslationY(0f);
        dispatchMoveFinished(holder);
        return false;
    }
}
