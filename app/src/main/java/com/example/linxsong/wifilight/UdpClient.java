package com.example.linxsong.wifilight;

import android.content.Intent;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

public class UdpClient implements Runnable {
    String TAG = "UdpClient";
    final static int udpPort = 6666;
    final static String hostIp = "255.255.255.255";
    private static DatagramSocket socket = null;
    private static DatagramPacket packetSend, packetRcv;
    private boolean udpLife = true; //udp生命线程
    private byte[] msgRcv = new byte[1024]; //接收消息

    public UdpClient() {
        super();
    }

    //返回udp生命线程因子是否存活
    public boolean isUdpLife() {
        if (udpLife) {
            return true;
        }

        return false;
    }

    //更改UDP生命线程因子
    public void setUdpLife(boolean b) {
        udpLife = b;
    }

    //发送消息
    public String send(String msgSend) {
        InetAddress hostAddress = null;

        try {
            hostAddress = InetAddress.getByName(hostIp);
        } catch (UnknownHostException e) {
            Log.i(TAG, "未找到服务器");
            e.printStackTrace();
        }

/*        try {
            socket = new DatagramSocket();
        } catch (SocketException e) {
            Log.i("udpClient","建立发送数据报失败");
            e.printStackTrace();
        }*/

        packetSend = new DatagramPacket(msgSend.getBytes(), msgSend.getBytes().length, hostAddress, udpPort);

        try {
            socket.send(packetSend);
        } catch (IOException e) {
            e.printStackTrace();
            Log.i(TAG, "发送失败");
        }
        //   socket.close();
        return msgSend;
    }

    @Override
    public void run() {

        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(3000);
        } catch (SocketException e) {
            Log.i(TAG, "建立接收数据报失败");
            e.printStackTrace();
        }
        packetRcv = new DatagramPacket(msgRcv, msgRcv.length);
        while (udpLife) {
            try {
                Log.i(TAG, "UDP监听");
                socket.receive(packetRcv);
                String RcvMsg = new String(packetRcv.getData(), packetRcv.getOffset(), packetRcv.getLength());
                //将收到的消息发给主界面
                Intent RcvIntent = new Intent();
                RcvIntent.setAction("udpRcvMsg");
                RcvIntent.putExtra("udpRcvMsg", RcvMsg);
                MainActivity.context.sendBroadcast(RcvIntent);

                Log.i("Rcv", RcvMsg);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Log.i(TAG, "UDP监听关闭");
        socket.close();
    }
}