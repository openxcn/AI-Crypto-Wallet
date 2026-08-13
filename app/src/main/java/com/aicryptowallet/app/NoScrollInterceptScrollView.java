package com.aicryptowallet.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/**
 * 不拦截子 View 长按手势的 ScrollView。
 * 标准 ScrollView 在子 View 长按时会因为检测到轻微移动就拦截触摸事件用于滚动，
 * 导致子 View 的 OnLongClickListener 永远触发不了。
 * 本类在判定子 View 是否处理长按前，不抢占触摸事件。
 */
public class NoScrollInterceptScrollView extends ScrollView {

    private int touchSlop;
    private float initialX, initialY;
    private boolean childBeingLongPressed = false;

    public NoScrollInterceptScrollView(Context context) {
        super(context);
        init(context);
    }

    public NoScrollInterceptScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public NoScrollInterceptScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                initialX = ev.getX();
                initialY = ev.getY();
                childBeingLongPressed = false;
                // DOWN 事件不拦截，让子 View 有机会处理长按
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - initialX);
                float dy = Math.abs(ev.getY() - initialY);
                // 只有当移动距离超过 touchSlop 时才拦截用于滚动
                if (dy > touchSlop || dx > touchSlop) {
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                childBeingLongPressed = false;
                return false;
            default:
                return super.onInterceptTouchEvent(ev);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                initialX = ev.getX();
                initialY = ev.getY();
                // 不消费 DOWN，让子 View 可以收到
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - initialX);
                float dy = Math.abs(ev.getY() - initialY);
                if (dy > touchSlop || dx > touchSlop) {
                    return super.onTouchEvent(ev);
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return super.onTouchEvent(ev);
            default:
                return super.onTouchEvent(ev);
        }
    }
}
