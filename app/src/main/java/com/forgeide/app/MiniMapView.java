package com.forgeide.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import io.github.rosemoe.sora.widget.CodeEditor;

/** Touch-navigable document overview. Draws a bounded sample, not thousands of text layouts. */
public final class MiniMapView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private CodeEditor editor;
    public MiniMapView(Context context){super(context);setContentDescription("Document minimap. Touch to jump to a line.");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    public void attach(CodeEditor editor){this.editor=editor;invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(editor==null)return;int count=editor.getLineCount();float unit=Math.min(3f,getHeight()/(float)Math.max(1,count));int step=Math.max(1,count/1500);
        paint.setColor(0x507F8B9C);for(int i=0;i<count;i+=step){CharSequence line=editor.getText().getLine(i);int indent=0;while(indent<line.length()&&Character.isWhitespace(line.charAt(indent)))indent++;float y=i*unit;canvas.drawRect(Math.min(getWidth(),indent*0.8f),y,Math.min(getWidth()-4,line.length()*0.8f),y+Math.max(1,unit*0.6f),paint);}
        paint.setColor(0x40EEA470);float y=editor.getCursor().getLeftLine()*unit;canvas.drawRect(0,Math.max(0,y-6),getWidth(),Math.min(getHeight(),y+18),paint);
    }
    @Override public boolean onTouchEvent(MotionEvent e){if(editor==null)return false;if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){int line=Math.min(editor.getLineCount()-1,Math.max(0,(int)(e.getY()/Math.max(1,getHeight())*editor.getLineCount())));editor.setSelection(line,0);invalidate();return true;}if(e.getAction()==MotionEvent.ACTION_UP){performClick();return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
