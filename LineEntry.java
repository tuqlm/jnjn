package com.retroforge.gba.engine;

import java.util.ArrayList;
import java.util.List;

public class LineEntry {
    public int id;
    public int offset;
    public int byteLen;
    public boolean multiline;
    public boolean repointable = true;
    public String original = "";
    public String vietnamese = "";
    public final List<Integer> pointers = new ArrayList<>();

    public boolean hasTranslation() {
        return vietnamese != null && !vietnamese.trim().isEmpty();
    }
}
