package app.organicmaps.widget.ruler;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.location.Location;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.util.StringUtils;
import app.organicmaps.util.ThemeUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * "Ruler" tool, similar to the one in Yandex Maps.
 * <p>
 * While active, the view lies above the map: a short tap adds a point, an existing point can be dragged, and all other
 * gestures (pan, zoom, rotate, tilt) are forwarded to the map. A polyline is drawn through the points with the
 * cumulative distance next to every point; the total length is shown in a panel with "undo" and "close" buttons.
 */
public class RulerView extends FrameLayout
{
  private static final String STATE_SUPER = "super";
  private static final String STATE_POINTS = "points";
  private static final String STATE_ACTIVE = "active";

  private static final float LINE_WIDTH_DP = 4f;
  private static final float OUTLINE_WIDTH_DP = 2f;
  private static final float POINT_RADIUS_DP = 7f;
  private static final float HIT_RADIUS_DP = 28f;
  private static final float LABEL_TEXT_SP = 13f;
  private static final float LABEL_PADDING_DP = 6f;
  private static final float LABEL_OFFSET_DP = 12f;

  // Points are stored as {lat, lon}.
  private final List<double[]> mPoints = new ArrayList<>();
  // Cumulative distance in meters from the first point to the i-th one.
  private final List<Double> mCumulative = new ArrayList<>();

  private final Paint mOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mPointFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mPointStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mLabelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mLabelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path mPath = new Path();
  private final RectF mLabelRect = new RectF();

  private final float mDensity;
  private final int mTouchSlop;
  private final long mTapTimeout;

  private TextView mTotalText;
  private View mUndoButton;

  @Nullable
  private View mMapView;
  @Nullable
  private Runnable mOnCloseListener;
  private boolean mActive;

  // Touch tracking.
  private float mDownX;
  private float mDownY;
  private long mDownTime;
  private boolean mMoved;
  private boolean mMultiTouch;
  private int mDragIndex = -1;

  public RulerView(@NonNull Context context)
  {
    this(context, null);
  }

  public RulerView(@NonNull Context context, @Nullable AttributeSet attrs)
  {
    this(context, attrs, 0);
  }

  public RulerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr)
  {
    super(context, attrs, defStyleAttr);
    mDensity = context.getResources().getDisplayMetrics().density;
    final ViewConfiguration configuration = ViewConfiguration.get(context);
    mTouchSlop = configuration.getScaledTouchSlop();
    mTapTimeout = ViewConfiguration.getLongPressTimeout();

    setWillNotDraw(false);
    initPaints(context);
    LayoutInflater.from(context).inflate(R.layout.ruler_panel, this, true);
    mTotalText = findViewById(R.id.ruler_total);
    mUndoButton = findViewById(R.id.ruler_undo);
    mUndoButton.setOnClickListener(v -> undo());
    mUndoButton.setOnLongClickListener(v -> {
      clear();
      return true;
    });
    findViewById(R.id.ruler_close).setOnClickListener(v -> close());

    // Hidden until the user starts the ruler.
    setVisibility(GONE);
    updatePanel();
  }

  private void initPaints(@NonNull Context context)
  {
    final int accent = ThemeUtils.getColor(context, androidx.appcompat.R.attr.colorAccent);
    final int surface = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorSurface);
    final int onSurface = ThemeUtils.getColor(context, com.google.android.material.R.attr.colorOnSurface);

    mOutlinePaint.setStyle(Paint.Style.STROKE);
    mOutlinePaint.setStrokeCap(Paint.Cap.ROUND);
    mOutlinePaint.setStrokeJoin(Paint.Join.ROUND);
    mOutlinePaint.setStrokeWidth((LINE_WIDTH_DP + 2 * OUTLINE_WIDTH_DP) * mDensity);
    mOutlinePaint.setColor(surface);

    mLinePaint.setStyle(Paint.Style.STROKE);
    mLinePaint.setStrokeCap(Paint.Cap.ROUND);
    mLinePaint.setStrokeJoin(Paint.Join.ROUND);
    mLinePaint.setStrokeWidth(LINE_WIDTH_DP * mDensity);
    mLinePaint.setColor(accent);

    mPointFillPaint.setStyle(Paint.Style.FILL);
    mPointFillPaint.setColor(surface);

    mPointStrokePaint.setStyle(Paint.Style.STROKE);
    mPointStrokePaint.setStrokeWidth(OUTLINE_WIDTH_DP * 1.5f * mDensity);
    mPointStrokePaint.setColor(accent);

    mLabelBgPaint.setStyle(Paint.Style.FILL);
    mLabelBgPaint.setColor(surface);

    mLabelTextPaint.setColor(onSurface);
    mLabelTextPaint.setTextSize(
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SP, getResources().getDisplayMetrics()));
    mLabelTextPaint.setFakeBoldText(true);
  }

  /**
   * @param mapView map view the gestures are forwarded to. It must have exactly the same bounds as this view.
   * @param onCloseListener called when the user closes the ruler.
   */
  public void attach(@NonNull View mapView, @Nullable Runnable onCloseListener)
  {
    mMapView = mapView;
    mOnCloseListener = onCloseListener;
  }

  public boolean isActive()
  {
    return mActive;
  }

  public void open()
  {
    if (mActive)
      return;
    mActive = true;
    setVisibility(VISIBLE);
    invalidate();
  }

  /**
   * Hides the ruler and forgets all points.
   */
  public void close()
  {
    if (!mActive)
      return;
    mActive = false;
    mDragIndex = -1;
    mPoints.clear();
    mCumulative.clear();
    setVisibility(GONE);
    updatePanel();
    if (mOnCloseListener != null)
      mOnCloseListener.run();
  }

  public void undo()
  {
    if (mPoints.isEmpty())
      return;
    mPoints.remove(mPoints.size() - 1);
    onPointsChanged();
  }

  public void clear()
  {
    if (mPoints.isEmpty())
      return;
    mPoints.clear();
    onPointsChanged();
  }

  /**
   * Sets the bottom margin of the info panel, e.g. to place it above the main menu.
   */
  public void setPanelBottomMargin(int marginPx)
  {
    final View panel = findViewById(R.id.ruler_panel);
    if (panel.getLayoutParams() instanceof MarginLayoutParams lp && lp.bottomMargin != marginPx)
    {
      lp.bottomMargin = marginPx;
      panel.setLayoutParams(lp);
    }
  }

  private void addPoint(float x, float y)
  {
    final double[] latLon = Framework.nativeScreenToLatLon(x, y);
    mPoints.add(new double[] {latLon[0], latLon[1]});
    onPointsChanged();
  }

  private void onPointsChanged()
  {
    mCumulative.clear();
    double total = 0;
    for (int i = 0; i < mPoints.size(); ++i)
    {
      if (i > 0)
        total += distanceMeters(mPoints.get(i - 1), mPoints.get(i));
      mCumulative.add(total);
    }
    updatePanel();
    invalidate();
  }

  private void updatePanel()
  {
    final double total = mCumulative.isEmpty() ? 0 : mCumulative.get(mCumulative.size() - 1);
    mTotalText.setText(mPoints.size() < 2 ? getContext().getString(R.string.ruler_hint) : formatDistance(total));
    mUndoButton.setEnabled(!mPoints.isEmpty());
    mUndoButton.setAlpha(mPoints.isEmpty() ? 0.4f : 1f);
  }

  @NonNull
  private String formatDistance(double meters)
  {
    return StringUtils.nativeFormatDistance(meters).toString(getContext());
  }

  private static double distanceMeters(@NonNull double[] a, @NonNull double[] b)
  {
    final float[] result = new float[1];
    Location.distanceBetween(a[0], a[1], b[0], b[1], result);
    return result[0];
  }

  // ------------------------------------------------------------------------------------------------------------
  // Drawing.
  // ------------------------------------------------------------------------------------------------------------

  @Override
  protected void onDraw(@NonNull Canvas canvas)
  {
    super.onDraw(canvas);
    if (!mActive || mPoints.isEmpty())
      return;

    // The map keeps moving under the overlay, so the geometry is re-projected on every frame.
    final int count = mPoints.size();
    final float[][] screen = new float[count][];
    for (int i = 0; i < count; ++i)
    {
      final double[] p = mPoints.get(i);
      screen[i] = Framework.nativeLatLonToScreen(p[0], p[1]);
    }

    if (count > 1)
    {
      mPath.rewind();
      mPath.moveTo(screen[0][0], screen[0][1]);
      for (int i = 1; i < count; ++i)
        mPath.lineTo(screen[i][0], screen[i][1]);
      canvas.drawPath(mPath, mOutlinePaint);
      canvas.drawPath(mPath, mLinePaint);
    }

    final float radius = POINT_RADIUS_DP * mDensity;
    for (int i = 0; i < count; ++i)
    {
      canvas.drawCircle(screen[i][0], screen[i][1], radius, mPointFillPaint);
      canvas.drawCircle(screen[i][0], screen[i][1], radius, mPointStrokePaint);
    }

    for (int i = 1; i < count; ++i)
      drawLabel(canvas, screen[i][0], screen[i][1], formatDistance(mCumulative.get(i)));

    // Keep following the map while it is moving or animating.
    postInvalidateOnAnimation();
  }

  private void drawLabel(@NonNull Canvas canvas, float x, float y, @NonNull String text)
  {
    final float padding = LABEL_PADDING_DP * mDensity;
    final float textWidth = mLabelTextPaint.measureText(text);
    final Paint.FontMetrics fm = mLabelTextPaint.getFontMetrics();
    final float height = fm.descent - fm.ascent + 2 * padding;
    final float width = textWidth + 2 * padding;

    // Place the label above-right of the point.
    final float left = x + LABEL_OFFSET_DP * mDensity;
    final float bottom = y - LABEL_OFFSET_DP * mDensity;
    mLabelRect.set(left, bottom - height, left + width, bottom);

    // Skip labels that are far away from the screen.
    if (mLabelRect.right < 0 || mLabelRect.left > getWidth() || mLabelRect.bottom < 0 || mLabelRect.top > getHeight())
      return;

    final float corner = height / 2;
    canvas.drawRoundRect(mLabelRect, corner, corner, mLabelBgPaint);
    canvas.drawText(text, mLabelRect.left + padding, mLabelRect.top + padding - fm.ascent, mLabelTextPaint);
  }

  // ------------------------------------------------------------------------------------------------------------
  // Touches.
  // ------------------------------------------------------------------------------------------------------------

  @Override
  public boolean onTouchEvent(@NonNull MotionEvent event)
  {
    if (!mActive || mMapView == null)
      return super.onTouchEvent(event);

    switch (event.getActionMasked())
    {
    case MotionEvent.ACTION_DOWN -> {
      mDownX = event.getX();
      mDownY = event.getY();
      mDownTime = event.getEventTime();
      mMoved = false;
      mMultiTouch = false;
      mDragIndex = findPointNear(mDownX, mDownY);
      if (mDragIndex >= 0)
        return true; // Dragging a point: the map must not move.
      mMapView.onTouchEvent(event);
      return true;
    }
    case MotionEvent.ACTION_POINTER_DOWN -> {
      mMultiTouch = true;
      if (mDragIndex >= 0)
        return true;
      mMapView.onTouchEvent(event);
      return true;
    }
    case MotionEvent.ACTION_MOVE -> {
      if (!mMoved && Math.hypot(event.getX() - mDownX, event.getY() - mDownY) > mTouchSlop)
        mMoved = true;
      if (mDragIndex >= 0)
      {
        if (mMoved && !mMultiTouch)
          movePoint(mDragIndex, event.getX(), event.getY());
        return true;
      }
      mMapView.onTouchEvent(event);
      return true;
    }
    case MotionEvent.ACTION_UP -> {
      if (mDragIndex >= 0)
      {
        mDragIndex = -1;
        return true;
      }
      final boolean isTap =
          !mMoved && !mMultiTouch && (event.getEventTime() - mDownTime) < mTapTimeout && getVisibleToTouch(event);
      if (isTap)
      {
        // Do not let the map treat it as a tap (it would select an object or open a place page).
        sendCancelToMap(event);
        addPoint(event.getX(), event.getY());
      }
      else
        mMapView.onTouchEvent(event);
      return true;
    }
    case MotionEvent.ACTION_POINTER_UP -> {
      if (mDragIndex >= 0)
        return true;
      mMapView.onTouchEvent(event);
      return true;
    }
    case MotionEvent.ACTION_CANCEL -> {
      mDragIndex = -1;
      mMapView.onTouchEvent(event);
      return true;
    }
    default -> {
      return super.onTouchEvent(event);
    }
    }
  }

  private boolean getVisibleToTouch(@NonNull MotionEvent event)
  {
    return event.getX() >= 0 && event.getY() >= 0 && event.getX() <= getWidth() && event.getY() <= getHeight();
  }

  private void sendCancelToMap(@NonNull MotionEvent source)
  {
    final MotionEvent cancel = MotionEvent.obtain(source);
    cancel.setAction(MotionEvent.ACTION_CANCEL);
    mMapView.onTouchEvent(cancel);
    cancel.recycle();
  }

  private int findPointNear(float x, float y)
  {
    final float hit = HIT_RADIUS_DP * mDensity;
    int best = -1;
    double bestDist = hit;
    for (int i = 0; i < mPoints.size(); ++i)
    {
      final double[] p = mPoints.get(i);
      final float[] s = Framework.nativeLatLonToScreen(p[0], p[1]);
      final double d = Math.hypot(s[0] - x, s[1] - y);
      if (d <= bestDist)
      {
        best = i;
        bestDist = d;
      }
    }
    return best;
  }

  private void movePoint(int index, float x, float y)
  {
    final double[] latLon = Framework.nativeScreenToLatLon(x, y);
    mPoints.set(index, new double[] {latLon[0], latLon[1]});
    onPointsChanged();
  }

  // ------------------------------------------------------------------------------------------------------------
  // State.
  // ------------------------------------------------------------------------------------------------------------

  @Nullable
  @Override
  protected Parcelable onSaveInstanceState()
  {
    final Bundle state = new Bundle();
    state.putParcelable(STATE_SUPER, super.onSaveInstanceState());
    state.putBoolean(STATE_ACTIVE, mActive);
    final double[] flat = new double[mPoints.size() * 2];
    for (int i = 0; i < mPoints.size(); ++i)
    {
      flat[2 * i] = mPoints.get(i)[0];
      flat[2 * i + 1] = mPoints.get(i)[1];
    }
    state.putDoubleArray(STATE_POINTS, flat);
    return state;
  }

  @Override
  protected void onRestoreInstanceState(Parcelable state)
  {
    if (!(state instanceof Bundle bundle))
    {
      super.onRestoreInstanceState(state);
      return;
    }

    super.onRestoreInstanceState(bundle.getParcelable(STATE_SUPER));
    final double[] flat = bundle.getDoubleArray(STATE_POINTS);
    mPoints.clear();
    if (flat != null)
    {
      for (int i = 0; i + 1 < flat.length; i += 2)
        mPoints.add(new double[] {flat[i], flat[i + 1]});
    }
    onPointsChanged();
    mActive = bundle.getBoolean(STATE_ACTIVE, false);
    setVisibility(mActive ? VISIBLE : GONE);
  }
}
