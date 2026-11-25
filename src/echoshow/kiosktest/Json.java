package echoshow.kiosktest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** JSON text to plain Map / List / String / Number / Boolean / null, with Android's org.json. */
final class Json {
    private Json() {}

    static Object lesen(String text) {
        try {
            return plain(new JSONTokener(text).nextValue());
        } catch (JSONException e) {
            return null;
        }
    }

    private static Object plain(Object o) throws JSONException {
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(plain(a.get(i)));
            return out;
        }
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, plain(j.get(k)));
            }
            return out;
        }
        return o == JSONObject.NULL ? null : o;
    }
}
