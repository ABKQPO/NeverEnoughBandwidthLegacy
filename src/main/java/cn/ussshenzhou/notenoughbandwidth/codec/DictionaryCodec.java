package cn.ussshenzhou.notenoughbandwidth.codec;

public interface DictionaryCodec extends PacketCodec {

    void loadDictionary(byte[] dictionary);
}
