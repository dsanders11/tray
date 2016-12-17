package qz.communication;

import org.bridj.Pointer;

public interface UvcControl {
    public long getBitmapIndex();

    public default Pointer<?> allocateDataPointer(int length) {
        switch (length) {
            case 1:
                return Pointer.allocateByte();
            case 2:
                return Pointer.allocateShort();
            case 3:
                return Pointer.allocateBytes(3);
            case 4:
                return Pointer.allocateInt();
            case 8:
                return Pointer.allocateLong();
        }

        throw new RuntimeException("Unkown control length " + length);
    }

    public default Pointer<?> createDataPointer(Pointer<?> currentValue, String value) {
        long offset = this.offset();
        int wLength = this.wLength();
        int ctrlLen = this.getCtrlLength();

        Pointer<?> pointer = null;

        // Allocate the data pointer for the full wLength buffer size and initialize
        switch (wLength) {
            case 1:
                assert currentValue.getTargetType().equals(Byte.class);
                pointer = Pointer.pointerToByte(currentValue.getByte());
                break;
            case 2:
                assert currentValue.getTargetType().equals(Short.class);
                pointer = Pointer.pointerToShort(currentValue.getShort());
                break;
            case 4:
                assert currentValue.getTargetType().equals(Integer.class);
                pointer = Pointer.pointerToInt(currentValue.getInt());
                break;
            case 8:
                assert currentValue.getTargetType().equals(Long.class);
                pointer = Pointer.pointerToLong(currentValue.getLong());
                break;
            default:
                throw new RuntimeException("Unkown control length " + wLength);
        }

        // Set the value at the offset, which most of the time will be 0
        switch (ctrlLen) {
            case 1:
                pointer.setByteAtOffset(offset, Byte.valueOf(value));
                break;
            case 2:
                pointer.setShortAtOffset(offset, Short.valueOf(value));
                break;
            case 4:
                pointer.setIntAtOffset(offset, Integer.valueOf(value));
                break;
            case 8:
                pointer.setLongAtOffset(offset, Long.valueOf(value));
                break;
            default:
                throw new RuntimeException("Unkown control length " + ctrlLen);
        }

        return pointer;
    }

    public class BoxedValue<T> {
        private T value;

        public BoxedValue(T value) {
            this.value = value;
        }

        public T get() {
            return this.value;
        }
    }

    public default BoxedValue<?> getControlData(Pointer<?> data) {
        long offset = this.offset();
        int ctrlLen = this.getCtrlLength();

        switch (ctrlLen) {
            case 1:
                return new BoxedValue<Byte>(data.getByteAtOffset(offset));
            case 2:
                return new BoxedValue<Short>(data.getShortAtOffset(offset));
            case 4:
                return new BoxedValue<Integer>(data.getIntAtOffset(offset));
            default:
                throw new RuntimeException("Unkown control length " + ctrlLen);
        }
    }

    public int wLength();

    public int offset();
    public int getCtrlLength();

    public byte getCtrlSelector();
    public String getName();
}
