package bv.dev.nakitel.audiovoicerecorder;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

/**
 *
 */
public class PlotView extends View {
    private static final String LOG_TAG = "bv_log";
    /* unused
    private String strText;
    private Drawable drawable;
    */
    private int color = Color.RED;
    private float textSize = 0;
    TextPaint textPaint;
    Paint.FontMetrics fontMetrics;
    Paint gridPaint;
    Paint contentPaint;
    Rect textXRect = new Rect();
    Rect textYRect = new Rect();

    // DecimalFormat decForm; // useless

    private double[] arrayX;
    private double[] arrayY;
    private String xTitle;
    private String yTitle;

    public PlotView(Context context) {
        super(context);
        init(null, 0);
    }

    public PlotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs, 0);
    }

    public PlotView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(attrs, defStyle);
    }

    private void init(AttributeSet attrs, int defStyle) {
        // Load attributes
        final TypedArray typedArray = getContext().obtainStyledAttributes(
                attrs, R.styleable.PlotView, defStyle, 0);

        /* unused
        strText = typedArray.getString(
                R.styleable.PlotView_exampleString);
        if (typedArray.hasValue(R.styleable.PlotView_exampleDrawable)) {
            drawable = typedArray.getDrawable(
                    R.styleable.PlotView_exampleDrawable);
            if (drawable != null) {
                drawable.setCallback(this);
            }
        }

        */
        color = typedArray.getColor(
                R.styleable.PlotView_exampleColor,
                color);
        // Use getDimensionPixelSize or getDimensionPixelOffset when dealing with
        // values that should fall on pixel boundaries.
        textSize = typedArray.getDimension(
                R.styleable.PlotView_exampleDimension,
                textSize);

        typedArray.recycle();

        textPaint = new TextPaint();
        textPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(textSize);
        textPaint.setColor(color);
        fontMetrics = textPaint.getFontMetrics();

        gridPaint = new Paint();
        gridPaint.setFlags(Paint.ANTI_ALIAS_FLAG);

        //noinspection deprecation
        gridPaint.setColor(getResources().getColor(R.color.colorGrayText));

        contentPaint = new Paint();
        contentPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
        contentPaint.setColor(color);
        contentPaint.setStrokeWidth(3);
        contentPaint.setStyle(Paint.Style.STROKE);

        /* not used
        decForm = new DecimalFormat("##E0");
        decForm.setMaximumIntegerDigits(2);
        //decForm.setMaximumFractionDigits(0); // does not work for floating point
        Log.d(LOG_TAG, "PlotView.init() decForm == " + decForm);
        */
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        try {
            // --------------------------
            // check conditions
            // keep references for case if member fields will be set other objects
            double[] arX = arrayX;
            double[] arY = arrayY;
            if (arX == null || arY == null || arX.length == 0 || arY.length == 0) {
                Log.w(LOG_TAG, "PlotView.onDraw() : arrays are empty ");
                return;
            }
            if (arX.length != arY.length) {
                Log.w(LOG_TAG, "PlotView.onDraw() : arrays have diff sizes : " + arX.length
                        + " / " + arY.length);
                return;
            }

            // --------------------------
            // limitations
            int pLeft = getPaddingLeft();
            int pTop = getPaddingTop();
            int pRight = getPaddingRight();
            int pBottom = getPaddingBottom();

            int contentWidth = getWidth() - pLeft - pRight;
            int contentHeight = getHeight() - pTop - pBottom;

            // --------------------------
            // find max dimensions for X labels
            double maxX = arX[arX.length - 1]; // by definition
            //String maxXText = (long) maxX + " " + xTitle; //old
            //String maxXText = decForm.format((long)maxX) + " " + xTitle; //not used
            String maxXText = formatTypedValue((long)maxX, xTitle);
            float textXWidth = textPaint.measureText(maxXText); // can use getTextBounds
            //float textXHeight = fontMetrics.bottom; // wrong
            float textXHeight = 0;
            if(maxXText.length() != 0) {
                textPaint.getTextBounds(maxXText, 0, maxXText.length() - 1, textXRect);
                textXHeight = textXRect.height();
            }

            // --------------------------
            // find max dimensions for Y labels
            double minY = arY[0];
            double maxY = arY[0];
            for (double y : arY) {
                if (y < minY) {
                    minY = y;
                }
                if (y > maxY) {
                    maxY = y;
                }
            }
            if(! isFinite(minY)) {
                minY = 0;
            }
            if(! isFinite(maxY)) {
                maxY = 0;
            }
            //String maxYText = (long) maxY + " " + yTitle; // old
            //String maxYText = decForm.format((long)maxY) + " " + yTitle; //not used
            String maxYText = formatTypedValue((long) maxY, yTitle);
            float textYWidth = textPaint.measureText(maxYText); // can use getTextBounds
            //float textYHeight = fontMetrics.bottom + fontMetrics.top; // wrong
            float textYHeight = 0;
            if(maxXText.length() != 0) {
                textPaint.getTextBounds(maxYText, 0, maxYText.length() - 1, textYRect);
                textYHeight = textYRect.height();
            }

            // max dimensions for text labels
            float textMaxWidth = Math.max(textXWidth, textYWidth);
            float textMaxHeight = Math.max(textXHeight, textYHeight);

            // --------------------------
            // axes counts
            int vertAxesCount = 0;
            if(textMaxWidth != 0) {
                vertAxesCount = (int) (contentWidth / textMaxWidth);
            } else {
                Log.w(LOG_TAG, "PlotView.onDraw(): textMaxWidth == 0 ");
            }
            int horizAxesCount = 0;
            if(textMaxHeight != 0) {
                horizAxesCount = (int) (contentHeight / 2.0 / textMaxHeight);
            } else {
                Log.w(LOG_TAG, "PlotView.onDraw(): textMaxHeight == 0 ");
            }

            // --------------------------
            // choose medium points for text labels
            // and distance between grid axes
            float spaceBtwVert = 0;
            String[] arVertXTitles = null;
            if(vertAxesCount != 0) {
                spaceBtwVert = contentWidth * 1.0f / (vertAxesCount - 1);
                int pointXSkip = arX.length / vertAxesCount;
                arVertXTitles = new String[vertAxesCount];
                for (int xIdx = 0; xIdx < arVertXTitles.length - 1; xIdx++) {
                    //arVertXTitles[xIdx] = (long) arX[xIdx * pointXSkip] + " " + xTitle; // old
                    //arVertXTitles[xIdx] = decForm.format((long) (arX[xIdx * pointXSkip])) + " " + xTitle; //not used
                    arVertXTitles[xIdx] = formatTypedValue((long) (arX[xIdx * pointXSkip]), xTitle);
                }
                arVertXTitles[arVertXTitles.length - 1] = formatTypedValue((long) arX[arX.length - 1], xTitle);
            } else {
                Log.w(LOG_TAG, "PlotView.onDraw(): vertAxesCount == 0 ; contentWidth == "
                        + contentWidth + "; textMaxWidth == " + textMaxWidth);
            }
            float spaceBtwHoriz = 0;
            String[] arHorizYTitles = null;
            if(horizAxesCount != 0) {
                spaceBtwHoriz = contentHeight * 1.0f / (horizAxesCount - 1);
                arHorizYTitles = new String[horizAxesCount];
                for (int yIdx = 0; yIdx < arHorizYTitles.length; yIdx++) {
                    // old
//                    arHorizYTitles[yIdx] = (long) ((yIdx * 1.0 / arHorizYTitles.length * (maxY - minY))
//                            + minY) + " " + yTitle;
//                    arHorizYTitles[yIdx] = decForm.format( (long) ((yIdx * 1.0
//                            / arHorizYTitles.length * (maxY - minY)) + minY) ) + " " + yTitle; //not used
                    arHorizYTitles[yIdx] = formatTypedValue((long) ((yIdx * 1.0
                            / arHorizYTitles.length * (maxY - minY)) + minY), yTitle);
                }
                //arHorizYTitles[0] = ""; // work around to do not overlap X and Y titles in (0, 0)
            } else {
                Log.w(LOG_TAG, "PlotView.onDraw(): horizAxesCount == 0 ; contentHeight == "
                        + contentHeight + "textMaxHeight == " + textMaxHeight);
            }

            //---------------- drawings -------------------------------------
            // (paddingLeft + x; paddingTop + contentHeight - y)
            //---------------------------
            //draw horiz axes and texts
            if(arHorizYTitles != null) {
                for (int axis = 0; axis < horizAxesCount; axis++) {
                    float curY = axis * spaceBtwHoriz;
                    canvas.drawLine(pLeft, pTop + curY, pLeft + contentWidth, pTop + curY, gridPaint);
                    // old
//                    canvas.drawText(arHorizYTitles[arHorizYTitles.length - 1 - axis],
//                            pLeft, pTop + curY, textPaint);
                    canvas.drawText(arHorizYTitles[arHorizYTitles.length - 1 - axis],
                            pLeft - textMaxWidth, pTop + curY, textPaint);
                    if (curY > contentHeight) {
                        Log.w(LOG_TAG, "PlotView.onDraw() : draw horiz axes and texts : curY > contentHeight");
                    }
                }
            } else {
                Log.w(LOG_TAG, "PlotView.onDraw(): arHorizYTitles == null");
            }

            //---------------------------
            // draw vert axes and texts
            if(arVertXTitles != null) {
                for (int axis = 0; axis < vertAxesCount; axis++) {
                    float curX = axis * spaceBtwVert;
                    canvas.drawLine(pLeft + curX, pTop, pLeft + curX, pTop + contentHeight, gridPaint);
                    canvas.drawText(arVertXTitles[axis], pLeft + curX - textMaxWidth,
                            pTop + contentHeight + textMaxHeight, textPaint);
                    // old
                    //canvas.drawText(arVertXTitles[axis], pLeft + curX, pTop + contentHeight, textPaint);
                    if (curX > contentWidth) {
                        Log.w(LOG_TAG, "PlotView.onDraw() : draw vert axes and texts : curX > contentWidth");
                    }
                }
            } else {
                Log.w(LOG_TAG, "PlotView.onDraw(): arVertXTitles == null ");
            }

            //---------------------------
            // draw graph
            /*
            (paddingLeft + x; paddingTop + contentHeight - y)
            arX[i] : maxX == x : Width
            arY[i] : maxY == y : Height
             */
            if(maxX != 0 && maxY != 0) {
                for (int arIdx = 0; arIdx < arX.length - 1; arIdx++) {
                    double x1 = arX[arIdx];
                    double y1 = arY[arIdx];
                    double x2 = arX[arIdx + 1];
                    double y2 = arY[arIdx + 1];
                    // skip NaNs and infinities ?
                    if(isNumsFinite(x1, x2, y1, y2)) {
                        canvas.drawLine((float) (x1 / maxX * contentWidth + pLeft),
                                (float) (contentHeight - (y1 / maxY * contentHeight) + pTop),
                                (float) (x2 / maxX * contentWidth + pLeft),
                                (float) (contentHeight - (y2 / maxY * contentHeight) + pTop),
                                contentPaint);
                    }
                }
            } else {
                Log.w(LOG_TAG, "PlotView.onDraw(): maxX == " + maxX + "; maxY == " + maxY);
            }

            /* can draw grid, save, draw plot, and instead or redrawing all, restore and redraw plot
            canvas.save();
            canvas.restore();
            */
        } catch(ArithmeticException ae) {
            Log.e(LOG_TAG, "PlotView.onDraw() : ArithmeticException : ", ae);
        }
    }

    private boolean isFinite(double val) {
        return ! Double.isNaN(val) && ! Double.isInfinite(val);
    }

    private boolean isNumsFinite(double ... vals) {
        for(int idx = 0; idx < vals.length; idx++) {
            if(! isFinite(vals[idx])) {
                //Log.d(LOG_TAG, "PlotView.isNumsFinite() : false on [" + idx + "] : " + vals[idx]);
                return false;
            }
        }
        return true;
    }

    /*
    public double[] getArX() {
        return arX;
    }*/

    public void setArX(double[] arX) {
        this.arrayX = arX;
    }

    /*
    public double[] getArY() {
        return arY;
    }*/

    public void setArY(double[] arY) {
        this.arrayY = arY;
    }

    /*
    public String getXTitle() {
        return xTitle;
    }*/

    public void setXTitle(String xTitle) {
        this.xTitle = xTitle;
    }

    /*
    public String getYTitle() {
        return yTitle;
    }*/

    public void setYTitle(String yTitle) {
        this.yTitle = yTitle;
    }

    /**
     * add SI unit to unit, normalize value, concat
     */
    private String formatTypedValue(long val, String unit) {
        long sign = (long) Math.signum(val);
        val = Math.abs(val);

        /* prev version
        String[] arSI = {"", "k", "M", "G", "T", "P", "E"};
        String unitPref = "";
        for(int prefIdx = 0; prefIdx < arSI.length; prefIdx++) {
            long div = (long) Math.pow(10, prefIdx * 3);
            if(val / div == 0) { // not divided
                // set prev
                val /= (long) Math.pow(10, (prefIdx - 1) * 3);
                unitPref = arSI[prefIdx - 1];
                break;
            }
        }
         */
        String[] arSI = {"k", "M", "G", "T", "P", "E"};
        String unitPref = "";
        long div = 1000;
        for (String unitSI : arSI) {
            if (val / div > 0) { // divided
                val /= div;
                unitPref = unitSI;
            } else { // not divided
                break;
            }
        }
        return sign * val + " " + unitPref + unit; // never
    }
}