package cn.ken.shoes.util;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
public class FileUtil {

    public static void clearDirectory(String directoryPath) {
        File directory = new File(directoryPath);
        // 检查路径是否存在且是目录
        if (!directory.exists()) {
           log.error("目录不存在：{}", directoryPath);
            return;
        }
        if (!directory.isDirectory()) {
            log.error("路径不是一个目录：{}", directoryPath);
            return;
        }

        // 删除目录中的所有内容
        deleteDirectoryContents(directory);
    }

    private static void deleteDirectoryContents(File directory) {
        File[] files = directory.listFiles();
        // 空目录或无法访问
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                // 如果是子目录，递归删除
                deleteDirectoryContents(file);
            }
            // 删除文件或空目录
            boolean deleted = file.delete();
            if (deleted) {
                log.info("已删除：{}", file.getAbsolutePath());
            } else {
                log.error("无法删除：{}", file.getAbsolutePath());
            }
        }
    }

    /**
     * 将指定的文件或目录压缩成 ZIP 文件
     *
     * @param sourceFilePath 要压缩的文件或目录路径
     * @param zipFilePath    生成的 ZIP 文件路径
     */
    public static void createZipFile(String sourceFilePath, String zipFilePath) {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFilePath))) {
            File sourceFile = new File(sourceFilePath);
            if (!sourceFile.exists()) {
                log.error("源文件或目录不存在：{}", sourceFilePath);
                return;
            }

            // 调用递归方法添加文件到 ZIP 输出流
            addFilesToZip(sourceFile, sourceFile.getName(), zos);

            log.info("压缩完成！ZIP 文件路径：{}", zipFilePath);
        } catch (IOException e) {
            log.error("压缩发生异常", e);
        }
    }

    /**
     * 递归地将文件或目录添加到 ZIP 输出流中
     *
     * @param file       当前文件或目录
     * @param entryName  ZIP 条目名称
     * @param zos        ZIP 输出流
     * @throws IOException 如果发生 I/O 错误
     */
    private static void addFilesToZip(File file, String entryName, ZipOutputStream zos) throws IOException {
        if (file.isDirectory()) {
            // 如果是目录，则递归处理子文件和子目录
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addFilesToZip(child, STR."\{entryName}/\{child.getName()}", zos);
                }
            }
        } else {
            // 如果是文件，则将其写入 ZIP 输出流
            try (FileInputStream fis = new FileInputStream(file)) {
                ZipEntry zipEntry = new ZipEntry(entryName);
                zos.putNextEntry(zipEntry);

                byte[] buffer = new byte[1024];
                int length;
                while ((length = fis.read(buffer)) >= 0) {
                    zos.write(buffer, 0, length);
                }

                zos.closeEntry();
            }
        }
    }
}