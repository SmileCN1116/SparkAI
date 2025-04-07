### 项目说明
1.本项目后端基于SpringBoot开发，JDK21+
2.默认端口8080，可自行修改application.properties文件
3.本项目前端基于Vue3+ElementPlus开发，自行安装element-plus依赖
5.因项目前端会识别ai回复的markdown格式，所以前端需要安装marked依赖将ai回复的markdown格式进行转义
6.本项目使用了dompurify防止xss攻击，所以前端需要安装dompurify依赖（因为这个项目本身是从其他项目拆分出来的）
### 文件结构说明
1.demo文件夹中为后端项目
2.vue-project文件夹中为前端项目
3.在demo/src/main/resources文件夹中需要在approperties文件中配置ai的各项参数

 各版本的hostUrl及其对应的domian参数，具体可以参考接口文档 https://www.xfyun.cn/doc/spark/Web.html
 Spark Lite      https://spark-api.xf-yun.com/v1.1/chat      domain参数为lite
 Spark Pro       https://spark-api.xf-yun.com/v3.1/chat      domain参数为generalv3
 Spark Pro-128K  https://spark-api.xf-yun.com/chat/pro-128k  domain参数为pro-128k
 Spark Max       https://spark-api.xf-yun.com/v3.5/chat      domain参数为generalv3.5
 Spark Max-32K   https://spark-api.xf-yun.com/chat/max-32k   domain参数为max-32k
 Spark 4.0 Ultra  https://spark-api.xf-yun.com/v4.0/chat      domain参数为4.0Ultra