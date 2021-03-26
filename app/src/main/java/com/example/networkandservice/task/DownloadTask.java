package com.example.networkandservice.task;

import android.os.AsyncTask;
import android.os.Environment;

import com.example.networkandservice.listener.DownloadListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.File;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
/*
 * 下载功能实现类
 * */

//第一个泛型参数指定为String,表示在执行AsyncTask时需要传入一个字符串参数给后台任务
//第二个泛型参数指定为Integer,表示用整型数据来作为进度显示单位
//第三个泛型数据为Integer，表示用整型数据来反馈执行结果
public class DownloadTask extends AsyncTask<String, Integer, Integer> {
    public static final int TYPE_SUCCESS = 0;
    public static final int TYPE_FAILED = 1;
    public static final int TYPE_PAUSED = 2;
    public static final int TYPE_CANCELED = 3;

    private final DownloadListener listener;
    private boolean isCanceled = false;
    private boolean isPaused = false;
    private int lastProgress;

    public DownloadTask(DownloadListener listener) {
        this.listener = listener;
    }

    @Override
    protected Integer doInBackground(String... params) {
        /*
         * 该方法中的所有代码都会在子线程中执行，应该在这里去处理所有的耗时任务
         * 任务一旦完成就可以通过return语句将任务的执行结果返回
         * 如果AsyncTask的第三个泛型参数指定的是void，就可以不返回任务的执行结果
         * 该方法中不可以进行UI操作，如果需要更新UI元素，如反馈当前任务的执行进度
         * 可以调用publishProgress(Progress...)方法来完成
         * */
        InputStream is = null;
        RandomAccessFile saveFile = null;
        File file = null;
        try {
            long downloadedLength = 0;//记录已下载的文件长度
            String downloadUrl = params[0];//从参数中获取到了URL地址
            //根据地址解析出下载的文件名,lastIndexOf返回当前字符串中，指定字符串最后出现的下标，substring返回指定下标之后的所有字符串
            String fileName = downloadUrl.substring(downloadUrl.lastIndexOf("/"));
            //指定文件下载到哪个目录
            String directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getPath();
            file = new File(directory + fileName);
            //判断Download目录中是否存在要下载的文件
            if (file.exists()) {
                //如果存在则读取已下载的字节数
                downloadedLength = file.length();
            }
            long contentLength = getContentLength(downloadUrl);
            if (contentLength == 0) {
                //如果长度为零，则说明文件有问题，直接返回TYPE_FAILED
                return TYPE_FAILED;
            } else if (contentLength == downloadedLength) {
                //文件总字节和已下载的字节数相等，说明已经下载完成
                return TYPE_SUCCESS;
            }
            //用OkHttp来发送一条网络请求
            OkHttpClient client = new OkHttpClient();
            //这里在请求中添加了一个header，用于告诉服务器从哪个字节开始下载，因为已下载过的部分就不需要下载了
            Request request = new Request.Builder().addHeader("RANGE", "bytes=" + downloadedLength + "-")
                    .url(downloadUrl).build();
            Response response = client.newCall(request).execute();
            if (response != null) {
                is = response.body().byteStream();
                saveFile = new RandomAccessFile(file, "rw");
                saveFile.seek(downloadedLength);//跳过已下载字节
                byte[] b = new byte[1024];
                int total = 0;
                int len;
                while ((len = is.read(b)) != -1) {
                    //下载过程中需判断用户是否进行取消或暂停操作
                    if (isCanceled) {
                        return TYPE_CANCELED;
                    } else if (isPaused) {
                        return TYPE_PAUSED;
                    } else {
                        //没有则不断从网络上读取数据写入本地，并实时更新进度
                        total += len;
                        saveFile.write(b, 0, len);
                        //计算已下载的百分比
                        int progress = (int) ((total + downloadedLength) * 100 / contentLength);
                        publishProgress(progress);
                    }
                }
                response.body().close();
                return TYPE_SUCCESS;

            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
                if (saveFile != null) {
                    saveFile.close();
                }
                if (isCanceled && file != null) {
                    file.delete();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return TYPE_FAILED;
    }

    @Override
    protected void onProgressUpdate(Integer... value) {
        /*
        当在后台任务中调用了publishProgress(Progress...)方法后，onProgressUpdate(Progress...)方法就会被调用，
        该方法中中携带的参数就是后台任务中传递过来的，
        在该方法中可以对UI进行操作，利用参数中的数值就可以对界面元素进行更新
        * */
        //从参数中获取当前下载进度
        int progress = value[0];
        //和上一次下载进度进行对比，如果有变化则调用DownloadListener的onProgress方法来通知下载进度更新
        if (progress > lastProgress) {
            listener.onProgress(progress);
            lastProgress = progress;
        }
    }

    @Override
    protected void onPostExecute(Integer status) {
        /*
         * 当后台任务执行完毕并通过return语句进行返回时，该方法就会被调用。
         * 返回的数据会作为参数传递到此方法中，可以利用返回的数据来进行一些UI操作
         * 比如提醒任务执行的结果，以及关闭掉进度条对话框等
         * */
        switch (status) {
            case TYPE_SUCCESS:
                listener.onSuccess();
                break;
            case TYPE_FAILED:
                listener.onFailed();
                break;
            case TYPE_PAUSED:
                listener.onPaused();
                break;
            case TYPE_CANCELED:
                listener.onCanceled();
                break;
            default:
                break;
        }

    }

    public void pauseDownload() {
        isPaused = true;
    }

    public void cancelDownload() {
        isCanceled = true;
    }

    
    private long getContentLength(String downloadUrl) throws IOException {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(downloadUrl).build();
        Response response = client.newCall(request).execute();
        if (response != null && response.isSuccessful()) {
            long contentLength = response.body().contentLength();
            response.close();
            return contentLength;
        }
        return 0;

    }


}
