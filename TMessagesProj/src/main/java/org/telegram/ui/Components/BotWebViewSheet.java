package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

public class BotWebViewSheet extends Dialog implements NotificationCenter.NotificationCenterDelegate {
    private final static int POLL_PERIOD = 60000;

    private final static SimpleFloatPropertyCompat<BotWebViewSheet> ACTION_BAR_TRANSITION_PROGRESS_VALUE = new SimpleFloatPropertyCompat<BotWebViewSheet>("actionBarTransitionProgress", obj -> obj.actionBarTransitionProgress, (obj, value) -> {
        obj.actionBarTransitionProgress = value;
        obj.frameLayout.invalidate();

        obj.actionBar.setAlpha(value);

        obj.updateLightStatusBar();
    }).setMultiplier(100f);
    private float actionBarTransitionProgress = 0f;
    private SpringAnimation springAnimation;

    private Boolean wasLightStatusBar;

    private SizeNotifierFrameLayout frameLayout;

    private long lastSwipeTime;

    private ChatAttachAlertBotWebViewLayout.WebViewSwipeContainer swipeContainer;
    private BotWebViewContainer webViewContainer;
    private ChatAttachAlertBotWebViewLayout.WebProgressView progressView;
    private Theme.ResourcesProvider resourcesProvider;
    private boolean ignoreLayout;

    private int currentAccount;
    private long botId;
    private long peerId;
    private long queryId;
    private int replyToMsgId;
    private boolean silent;
    private String buttonText;

    private Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint dimPaint = new Paint();
    private Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ActionBar actionBar;
    private Drawable actionBarShadow;

    private boolean dismissed;

    private Activity parentActivity;

    private boolean mainButtonWasVisible, mainButtonProgressWasVisible;
    private TextView mainButton;
    private RadialProgressView radialProgressView;

    private VerticalPositionAutoAnimator mainButtonAutoAnimator, radialProgressAutoAnimator;

    private Runnable pollRunnable = () -> {
        if (!dismissed) {
            TLRPC.TL_messages_prolongWebView prolongWebView = new TLRPC.TL_messages_prolongWebView();
            prolongWebView.bot = MessagesController.getInstance(currentAccount).getInputUser(botId);
            prolongWebView.peer = MessagesController.getInstance(currentAccount).getInputPeer(peerId);
            prolongWebView.query_id = queryId;
            prolongWebView.silent = silent;
            if (replyToMsgId != 0) {
                prolongWebView.reply_to_msg_id = replyToMsgId;
                prolongWebView.flags |= 1;
            }
            ConnectionsManager.getInstance(currentAccount).sendRequest(prolongWebView, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (dismissed) {
                    return;
                }
                if (error != null) {
                    dismiss();
                } else {
                    AndroidUtilities.runOnUIThread(this.pollRunnable, POLL_PERIOD);
                }
            }));
        }
    };

    public BotWebViewSheet(@NonNull Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, R.style.TransparentDialog);
        this.resourcesProvider = resourcesProvider;

        swipeContainer = new ChatAttachAlertBotWebViewLayout.WebViewSwipeContainer(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int availableHeight = MeasureSpec.getSize(heightMeasureSpec);

                int padding;
                if (!AndroidUtilities.isTablet() && AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
                    padding = (int) (availableHeight / 3.5f);
                } else {
                    padding = (availableHeight / 5 * 2);
                }
                if (padding < 0) {
                    padding = 0;
                }

                if (getOffsetY() != padding && !dismissed) {
                    ignoreLayout = true;
                    setOffsetY(padding);
                    ignoreLayout = false;
                }

                if (AndroidUtilities.isTablet() && !AndroidUtilities.isInMultiwindow && !AndroidUtilities.isSmallTablet()) {
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec((int) (Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * 0.8f), MeasureSpec.EXACTLY);
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec) - ActionBar.getCurrentActionBarHeight() - AndroidUtilities.statusBarHeight + AndroidUtilities.dp(24) - (mainButtonWasVisible ? mainButton.getLayoutParams().height : 0), MeasureSpec.EXACTLY));
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) {
                    return;
                }
                super.requestLayout();
            }
        };
        webViewContainer = new BotWebViewContainer(context, resourcesProvider, getColor(Theme.key_windowBackgroundWhite));

        webViewContainer.getWebView().setVerticalScrollBarEnabled(false);
        webViewContainer.setDelegate(new BotWebViewContainer.Delegate() {
            private boolean sentWebViewData;

            @Override
            public void onCloseRequested() {
                dismiss();
            }

            @Override
            public void onSendWebViewData(String data) {
                if (sentWebViewData) {
                    return;
                }
                sentWebViewData = true;

                TLRPC.TL_messages_sendWebViewData sendWebViewData = new TLRPC.TL_messages_sendWebViewData();
                sendWebViewData.bot = MessagesController.getInstance(currentAccount).getInputUser(botId);
                sendWebViewData.random_id = Utilities.random.nextLong();
                sendWebViewData.button_text = buttonText;
                sendWebViewData.data = data;
                ConnectionsManager.getInstance(currentAccount).sendRequest(sendWebViewData, (response, error) -> AndroidUtilities.runOnUIThread(()-> dismiss()));
            }

            @Override
            public void onWebAppExpand() {
                if (System.currentTimeMillis() - lastSwipeTime <= 1000 || swipeContainer.isSwipeInProgress()) {
                    return;
                }
                swipeContainer.stickTo(-swipeContainer.getOffsetY() + swipeContainer.getTopActionBarOffsetY());
            }

            @Override
            public void onSetupMainButton(boolean isVisible, boolean isActive, String text, int color, int textColor, boolean isProgressVisible) {
                mainButton.setClickable(isActive);
                mainButton.setText(text);
                mainButton.setTextColor(textColor);
                mainButton.setBackground(Theme.createSelectorWithBackgroundDrawable(color, Theme.getColor(Theme.key_listSelector)));
                if (isVisible != mainButtonWasVisible) {
                    mainButtonWasVisible = isVisible;
                    mainButton.animate().cancel();
                    if (isVisible) {
                        mainButton.setAlpha(0f);
                        mainButton.setVisibility(View.VISIBLE);
                    }
                    mainButton.animate().alpha(isVisible ? 1f : 0f).setDuration(150).setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (!isVisible) {
                                mainButton.setVisibility(View.GONE);
                            }
                            swipeContainer.requestLayout();
                        }
                    }).start();
                }
                radialProgressView.setProgressColor(textColor);
                if (isProgressVisible != mainButtonProgressWasVisible) {
                    mainButtonProgressWasVisible = isProgressVisible;
                    radialProgressView.animate().cancel();
                    if (isProgressVisible) {
                        radialProgressView.setAlpha(0f);
                        radialProgressView.setVisibility(View.VISIBLE);
                    }
                    radialProgressView.animate().alpha(isProgressVisible ? 1f : 0f)
                            .scaleX(isProgressVisible ? 1f : 0.1f)
                            .scaleY(isProgressVisible ? 1f : 0.1f)
                            .setDuration(250)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    if (!isProgressVisible) {
                                        radialProgressView.setVisibility(View.GONE);
                                    }
                                }
                            }).start();
                }
            }
        });

        linePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        linePaint.setStrokeWidth(AndroidUtilities.dp(4));
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        backgroundPaint.setColor(getColor(Theme.key_windowBackgroundWhite));
        dimPaint.setColor(0x40000000);
        frameLayout = new SizeNotifierFrameLayout(context) {
            {
                setWillNotDraw(false);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);

                AndroidUtilities.rectTmp.set(0, 0, getWidth(), getHeight());
                canvas.drawRect(AndroidUtilities.rectTmp, dimPaint);

                float radius = AndroidUtilities.dp(16) * (1f - actionBarTransitionProgress);
                AndroidUtilities.rectTmp.set(0, AndroidUtilities.lerp(swipeContainer.getTranslationY(), 0, actionBarTransitionProgress), getWidth(), getHeight() + radius);
                canvas.drawRoundRect(AndroidUtilities.rectTmp, radius, radius, backgroundPaint);
            }

            @Override
            public void draw(Canvas canvas) {
                super.draw(canvas);

                linePaint.setColor(Theme.getColor(Theme.key_dialogGrayLine));
                linePaint.setAlpha((int) (linePaint.getAlpha() * (1f - Math.min(0.5f, actionBarTransitionProgress) / 0.5f)));

                canvas.save();
                float scale = 1f - actionBarTransitionProgress;
                float y = AndroidUtilities.lerp(swipeContainer.getTranslationY(), AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() / 2f, actionBarTransitionProgress) + AndroidUtilities.dp(12);
                canvas.scale(scale, scale, getWidth() / 2f, y);
                canvas.drawLine(getWidth() / 2f - AndroidUtilities.dp(16), y, getWidth() / 2f + AndroidUtilities.dp(16), y, linePaint);
                canvas.restore();

                actionBarShadow.setAlpha((int) (actionBar.getAlpha() * 0xFF));
                y = actionBar.getY() + actionBar.getTranslationY() + actionBar.getHeight();
                actionBarShadow.setBounds(0, (int)y, getWidth(), (int)(y + actionBarShadow.getIntrinsicHeight()));
                actionBarShadow.draw(canvas);
            }

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN && event.getY() < AndroidUtilities.lerp(swipeContainer.getTranslationY(), 0, actionBarTransitionProgress)) {
                    dismiss();
                    return true;
                }
                return super.onTouchEvent(event);
            }
        };
        frameLayout.setDelegate((keyboardHeight, isWidthGreater) -> {
            if (keyboardHeight > AndroidUtilities.dp(20)) {
                swipeContainer.stickTo(-swipeContainer.getOffsetY() + swipeContainer.getTopActionBarOffsetY());
            }
        });
        frameLayout.addView(swipeContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 24, 0, 0));

        mainButton = new TextView(context);
        mainButton.setVisibility(View.GONE);
        mainButton.setAlpha(0f);
        mainButton.setSingleLine();
        mainButton.setGravity(Gravity.CENTER);
        mainButton.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        int padding = AndroidUtilities.dp(16);
        mainButton.setPadding(padding, 0, padding, 0);
        mainButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        mainButton.setOnClickListener(v -> webViewContainer.onMainButtonPressed());
        frameLayout.addView(mainButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM));
        mainButtonAutoAnimator = VerticalPositionAutoAnimator.attach(mainButton);

        radialProgressView = new RadialProgressView(context);
        radialProgressView.setSize(AndroidUtilities.dp(18));
        radialProgressView.setAlpha(0f);
        radialProgressView.setScaleX(0.1f);
        radialProgressView.setScaleY(0.1f);
        radialProgressView.setVisibility(View.GONE);
        frameLayout.addView(radialProgressView, LayoutHelper.createFrame(28, 28, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 10, 10));
        radialProgressAutoAnimator = VerticalPositionAutoAnimator.attach(radialProgressView);

        actionBarShadow = ContextCompat.getDrawable(getContext(), R.drawable.header_shadow).mutate();

        actionBar = new ActionBar(context, resourcesProvider);
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarWhiteSelector), false);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    dismiss();
                }
            }
        });
        actionBar.setAlpha(0f);
        frameLayout.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        frameLayout.addView(progressView = new ChatAttachAlertBotWebViewLayout.WebProgressView(context, resourcesProvider), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 0, 0, 0, 0));
        webViewContainer.setWebViewProgressListener(progress -> {
            progressView.setLoadProgressAnimated(progress);
            if (progress == 1f) {
                ValueAnimator animator = ValueAnimator.ofFloat(1, 0).setDuration(200);
                animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
                animator.addUpdateListener(animation -> progressView.setAlpha((Float) animation.getAnimatedValue()));
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        progressView.setVisibility(View.GONE);
                    }
                });
                animator.start();
            }
        });

        swipeContainer.addView(webViewContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        swipeContainer.setWebView(webViewContainer.getWebView());
        swipeContainer.setScrollListener(()->{
            if (swipeContainer.getSwipeOffsetY() > 0) {
                dimPaint.setAlpha((int) (0x40 * (1f - swipeContainer.getSwipeOffsetY() / (float)swipeContainer.getHeight())));
            } else {
                dimPaint.setAlpha(0x40);
            }
            frameLayout.invalidate();
            webViewContainer.invalidateViewPortHeight();

            if (springAnimation != null) {
                float progress = (1f - Math.min(swipeContainer.getTopActionBarOffsetY(), swipeContainer.getTranslationY() - swipeContainer.getTopActionBarOffsetY()) / swipeContainer.getTopActionBarOffsetY());
                float newPos = (progress > 0.5f ? 1 : 0) * 100f;
                if (springAnimation.getSpring().getFinalPosition() != newPos) {
                    springAnimation.getSpring().setFinalPosition(newPos);
                    springAnimation.start();
                }
            }
            float offsetY = Math.max(0, swipeContainer.getSwipeOffsetY());
            mainButtonAutoAnimator.setOffsetY(offsetY);
            radialProgressAutoAnimator.setOffsetY(offsetY);
            lastSwipeTime = System.currentTimeMillis();
        });
        swipeContainer.setScrollEndListener(()-> webViewContainer.invalidateViewPortHeight(true));
        swipeContainer.setDelegate(this::dismiss);
        swipeContainer.setTopActionBarOffsetY(ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight - AndroidUtilities.dp(24));

        setContentView(frameLayout, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setParentActivity(Activity parentActivity) {
        this.parentActivity = parentActivity;
    }

    private void updateLightStatusBar() {
        int color = Theme.getColor(Theme.key_windowBackgroundWhite, null, true);
        boolean lightStatusBar = ColorUtils.calculateLuminance(color) >= 0.9 && actionBarTransitionProgress >= 0.85f;

        if (wasLightStatusBar != null && wasLightStatusBar == lightStatusBar) {
            return;
        }
        wasLightStatusBar = lightStatusBar;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = frameLayout.getSystemUiVisibility();
            if (lightStatusBar) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            frameLayout.setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
        window.setWindowAnimations(R.style.DialogNoAnimation);

        WindowManager.LayoutParams params = window.getAttributes();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.dimAmount = 0;
        params.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        window.setAttributes(params);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.setStatusBarColor(Color.TRANSPARENT);
        }

        frameLayout.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            frameLayout.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
                return insets;
            });
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int color = Theme.getColor(Theme.key_windowBackgroundWhite, null, true);
            AndroidUtilities.setLightNavigationBar(window, ColorUtils.calculateLuminance(color) >= 0.9);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (springAnimation == null) {
            springAnimation = new SpringAnimation(this, ACTION_BAR_TRANSITION_PROGRESS_VALUE)
                .setSpring(new SpringForce()
                    .setStiffness(1200f)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                );
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (springAnimation != null) {
            springAnimation.cancel();
            springAnimation = null;
        }
    }

    public void requestWebView(int currentAccount, long peerId, long botId, String buttonText, String buttonUrl, boolean simple, int replyToMsgId, boolean silent) {
        this.currentAccount = currentAccount;
        this.peerId = peerId;
        this.botId = botId;
        this.replyToMsgId = replyToMsgId;
        this.silent = silent;
        this.buttonText = buttonText;

        actionBar.setTitle(UserObject.getUserName(MessagesController.getInstance(currentAccount).getUser(botId)));
        ActionBarMenu menu = actionBar.createMenu();
        menu.removeAllViews();

        ActionBarMenuItem otherItem = menu.addItem(0, R.drawable.ic_ab_other);
        otherItem.addSubItem(R.id.menu_open_bot, R.drawable.msg_bot, LocaleController.getString(R.string.BotWebViewOpenBot));
        otherItem.addSubItem(R.id.menu_reload_page, R.drawable.msg_retry, LocaleController.getString(R.string.BotWebViewReloadPage));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    dismiss();
                } else if (id == R.id.menu_open_bot) {
                    Bundle bundle = new Bundle();
                    bundle.putLong("user_id", botId);
                    if (parentActivity instanceof LaunchActivity) {
                        ((LaunchActivity) parentActivity).presentFragment(new ChatActivity(bundle));
                    }
                    dismiss();
                } else if (id == R.id.menu_reload_page) {
                    webViewContainer.getWebView().animate().cancel();
                    webViewContainer.getWebView().animate().alpha(0).start();
                    requestWebView(currentAccount, peerId, botId, buttonText, buttonUrl, simple, replyToMsgId, silent);
                }
            }
        });

        boolean hasThemeParams = true;
        String themeParams = null;
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("bg_color", getColor(Theme.key_windowBackgroundWhite));
            jsonObject.put("text_color", getColor(Theme.key_windowBackgroundWhiteBlackText));
            jsonObject.put("hint_color", getColor(Theme.key_windowBackgroundWhiteHintText));
            jsonObject.put("link_color", getColor(Theme.key_windowBackgroundWhiteLinkText));
            jsonObject.put("button_color", getColor(Theme.key_featuredStickers_addButton));
            jsonObject.put("button_text_color", getColor(Theme.key_featuredStickers_buttonText));
            themeParams = jsonObject.toString();
        } catch (Exception e) {
            FileLog.e(e);
            hasThemeParams = false;
        }

        webViewContainer.setBotUser(MessagesController.getInstance(currentAccount).getUser(botId));
        webViewContainer.loadFlicker(currentAccount, botId);
        if (simple) {
            TLRPC.TL_messages_requestSimpleWebView req = new TLRPC.TL_messages_requestSimpleWebView();
            req.bot = MessagesController.getInstance(currentAccount).getInputUser(botId);
            if (hasThemeParams) {
                req.theme_params = new TLRPC.TL_dataJSON();
                req.theme_params.data = themeParams;
                req.flags |= 1;
            }
            req.url = buttonUrl;

            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(()->{
                if (response instanceof TLRPC.TL_simpleWebViewResultUrl) {
                    TLRPC.TL_simpleWebViewResultUrl resultUrl = (TLRPC.TL_simpleWebViewResultUrl) response;
                    webViewContainer.loadUrl(resultUrl.url);
                }
            }));
        } else {
            TLRPC.TL_messages_requestWebView req = new TLRPC.TL_messages_requestWebView();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(peerId);
            req.bot = MessagesController.getInstance(currentAccount).getInputUser(botId);
            if (buttonUrl != null) {
                req.url = buttonUrl;
                req.flags |= 2;
            }

            if (replyToMsgId != 0) {
                req.reply_to_msg_id = replyToMsgId;
                req.flags |= 1;
            }

            if (hasThemeParams) {
                req.theme_params = new TLRPC.TL_dataJSON();
                req.theme_params.data = themeParams;
                req.flags |= 4;
            }

            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_webViewResultUrl) {
                    TLRPC.TL_webViewResultUrl resultUrl = (TLRPC.TL_webViewResultUrl) response;
                    queryId = resultUrl.query_id;
                    webViewContainer.loadUrl(resultUrl.url);

                    AndroidUtilities.runOnUIThread(pollRunnable, POLL_PERIOD);
                }
            }));
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.webViewResultSent);
        }
    }

    private int getColor(String key) {
        Integer color;
        if (resourcesProvider != null) {
            color = resourcesProvider.getColor(key);
        } else {
            color = Theme.getColor(key);
        }
        return color != null ? color : Theme.getColor(key);
    }

    @Override
    public void show() {
        frameLayout.setAlpha(0f);
        frameLayout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                v.removeOnLayoutChangeListener(this);

                swipeContainer.setSwipeOffsetY(swipeContainer.getHeight());
                frameLayout.setAlpha(1f);

                new SpringAnimation(swipeContainer, ChatAttachAlertBotWebViewLayout.WebViewSwipeContainer.SWIPE_OFFSET_Y, 0)
                        .setSpring(new SpringForce(0)
                                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                                .setStiffness(500.0f)
                        ).start();
            }
        });
        super.show();
    }

    @Override
    public void dismiss() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        AndroidUtilities.cancelRunOnUIThread(pollRunnable);

        webViewContainer.destroyWebView();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.webViewResultSent);

        swipeContainer.stickTo(swipeContainer.getHeight() + frameLayout.measureKeyboardHeight(), super::dismiss);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.webViewResultSent) {
            long queryId = (long) args[0];

            if (this.queryId == queryId) {
                dismiss();
            }
        }
    }
}
