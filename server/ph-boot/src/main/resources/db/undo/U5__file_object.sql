-- 回滚 V5：文件对象表。
--
-- 注意：这张表只记元数据，**字节在存储里**（local 驱动是磁盘目录）。回滚前若要一并清干净，
-- 人工步骤见 server/README.md——SQL 回滚不负责删文件，否则一次误回滚会连带删掉用户照片。
DROP TABLE IF EXISTS `file_object`;
