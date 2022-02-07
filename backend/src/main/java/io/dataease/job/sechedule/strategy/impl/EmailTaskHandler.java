package io.dataease.job.sechedule.strategy.impl;

import io.dataease.auth.entity.SysUserEntity;
import io.dataease.auth.entity.TokenInfo;
import io.dataease.auth.service.AuthUserService;
import io.dataease.auth.service.impl.AuthUserServiceImpl;
import io.dataease.auth.util.JWTUtils;
import io.dataease.base.mapper.ext.ExtTaskMapper;
import io.dataease.commons.utils.CommonBeanFactory;
import io.dataease.commons.utils.LogUtil;
import io.dataease.commons.utils.ServletUtils;
import io.dataease.job.sechedule.ScheduleManager;
import io.dataease.job.sechedule.strategy.TaskHandler;
import io.dataease.plugins.common.entity.GlobalTaskEntity;
import io.dataease.plugins.common.entity.GlobalTaskInstance;
import io.dataease.plugins.config.SpringContextUtil;
import io.dataease.plugins.xpack.email.dto.request.XpackPixelEntity;
import io.dataease.plugins.xpack.email.dto.response.XpackEmailTemplateDTO;
import io.dataease.plugins.xpack.email.service.EmailXpackService;
import io.dataease.service.system.EmailService;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.quartz.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class EmailTaskHandler extends TaskHandler implements Job {

    private static final Integer RUNING = 0;
    private static final Integer SUCCESS = 1;
    private static final Integer ERROR = -1;

    @Resource
    private AuthUserServiceImpl authUserServiceImpl;

    @Override
    protected JobDataMap jobDataMap(GlobalTaskEntity taskEntity) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("taskEntity", taskEntity);
        EmailXpackService emailXpackService = SpringContextUtil.getBean(EmailXpackService.class);
        XpackEmailTemplateDTO emailTemplateDTO = emailXpackService.emailTemplate(taskEntity.getTaskId());
        jobDataMap.put("emailTemplate", emailTemplateDTO);
        SysUserEntity creator = authUserServiceImpl.getUserByIdNoCache(taskEntity.getCreator());
        jobDataMap.put("creator", creator);
        return jobDataMap;
    }

    public EmailTaskHandler proxy() {
        return CommonBeanFactory.getBean(EmailTaskHandler.class);
    }

    @Override
    protected Boolean taskIsRunning(Long taskId) {
        ExtTaskMapper extTaskMapper = CommonBeanFactory.getBean(ExtTaskMapper.class);
        return extTaskMapper.runningCount(taskId) > 0;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        // 插件没有加载 空转
        if (!CommonBeanFactory.getBean(AuthUserService.class).pluginLoaded())
            return;

        JobDataMap jobDataMap = context.getJobDetail().getJobDataMap();
        GlobalTaskEntity taskEntity = (GlobalTaskEntity) jobDataMap.get("taskEntity");
        ScheduleManager scheduleManager = SpringContextUtil.getBean(ScheduleManager.class);
        if (taskExpire(taskEntity.getEndTime())) {
            removeTask(scheduleManager, taskEntity);
            return;
        }
        if (taskIsRunning(taskEntity.getTaskId())) {
            LogUtil.info("Skip synchronization task: {} ,due to task status is {}",
                    taskEntity.getTaskId(), "running");
            return;
        }

        GlobalTaskInstance taskInstance = buildInstance(taskEntity);
        Long instanceId = saveInstance(taskInstance);
        taskInstance.setInstanceId(instanceId);

        XpackEmailTemplateDTO emailTemplate = (XpackEmailTemplateDTO) jobDataMap.get("emailTemplate");
        SysUserEntity creator = (SysUserEntity) jobDataMap.get("creator");
        LogUtil.info("start execute send panel report task...");
        proxy().sendReport(taskInstance, emailTemplate, creator);

    }

    @Override
    public void resetRunningInstance(Long taskId) {
        ExtTaskMapper extTaskMapper = CommonBeanFactory.getBean(ExtTaskMapper.class);
        extTaskMapper.resetRunnings(taskId);
    }

    public Long saveInstance(GlobalTaskInstance taskInstance) {
        EmailXpackService emailXpackService = SpringContextUtil.getBean(EmailXpackService.class);
        return emailXpackService.saveInstance(taskInstance);
    }

    private GlobalTaskInstance buildInstance(GlobalTaskEntity taskEntity) {
        GlobalTaskInstance taskInstance = new GlobalTaskInstance();
        taskInstance.setTaskId(taskEntity.getTaskId());
        taskInstance.setStatus(RUNING);
        taskInstance.setExecuteTime(System.currentTimeMillis());
        return taskInstance;
    }

    private void success(GlobalTaskInstance taskInstance) {
        taskInstance.setStatus(SUCCESS);
        taskInstance.setFinishTime(System.currentTimeMillis());
        EmailXpackService emailXpackService = SpringContextUtil.getBean(EmailXpackService.class);
        emailXpackService.saveInstance(taskInstance);
    }

    private void error(GlobalTaskInstance taskInstance, Throwable t) {
        taskInstance.setStatus(ERROR);
        taskInstance.setInfo(t.getMessage());
        EmailXpackService emailXpackService = SpringContextUtil.getBean(EmailXpackService.class);
        emailXpackService.saveInstance(taskInstance);
    }

    @Async("priorityExecutor")
    public void sendReport(GlobalTaskInstance taskInstance, XpackEmailTemplateDTO emailTemplateDTO,
            SysUserEntity user) {
        EmailXpackService emailXpackService = SpringContextUtil.getBean(EmailXpackService.class);
        try {

            String panelId = emailTemplateDTO.getPanelId();
            String url = panelUrl(panelId);
            String token = tokenByUser(user);
            XpackPixelEntity xpackPixelEntity = buildPixel(emailTemplateDTO);
            LogUtil.info("url is " + url);
            LogUtil.info("token is " + token);
            byte[] bytes = emailXpackService.printData(url, token, xpackPixelEntity);
            LogUtil.info("picture of " + url + " is finished");
            // 下面继续执行发送邮件的
            String recipients = emailTemplateDTO.getRecipients();
            byte[] content = emailTemplateDTO.getContent();
            EmailService emailService = SpringContextUtil.getBean(EmailService.class);

            String contentStr = "";
            if (ObjectUtils.isNotEmpty(content)) {
                contentStr = new String(content, "UTF-8");
            }
            emailService.sendWithImage(recipients, emailTemplateDTO.getTitle(),
                    contentStr, bytes);

            Thread.sleep(10000);
            success(taskInstance);
        } catch (Exception e) {
            error(taskInstance, e);
            LogUtil.error(e.getMessage(), e);
        }
    }

    private XpackPixelEntity buildPixel(XpackEmailTemplateDTO emailTemplateDTO) {
        XpackPixelEntity pixelEntity = new XpackPixelEntity();
        String pixelStr = emailTemplateDTO.getPixel();
        if (StringUtils.isBlank(pixelStr))
            return null;
        String[] arr = pixelStr.split("\\*");
        if (arr.length != 2)
            return null;
        try {
            int x = Integer.parseInt(arr[0].trim());
            int y = Integer.parseInt(arr[1].trim());
            pixelEntity.setX(String.valueOf(x));
            pixelEntity.setY(String.valueOf(y));
            return pixelEntity;
        } catch (Exception e) {
            return null;
        }
    }

    private String tokenByUser(SysUserEntity user) {
        TokenInfo tokenInfo = TokenInfo.builder().userId(user.getUserId()).username(user.getUsername()).build();
        String token = JWTUtils.sign(tokenInfo, user.getPassword());

        return token;
    }

    private String panelUrl(String panelId) {
        String domain = ServletUtils.domain();
        return domain + "/#/previewScreenShot/" + panelId + "/true";
    }

}