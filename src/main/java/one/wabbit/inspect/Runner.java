package one.wabbit.inspect;

import java.lang.reflect.InvocationTargetException;

public class Runner {
    public static void main(String[] args) {
        var className = args[0];
        var methodName = args[1];

        try {
            Class.forName(className).getMethod(methodName).invoke(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
