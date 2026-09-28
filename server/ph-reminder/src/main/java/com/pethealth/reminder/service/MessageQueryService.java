package com.pethealth.reminder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pethealth.reminder.api.MessageQueryApi;
import com.pethealth.reminder.domain.Message;
import com.pethealth.reminder.mapper.MessageMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** {@link MessageQueryApi} 的实现。 */
@Service
public class MessageQueryService implements MessageQueryApi {

    private final MessageMapper messageMapper;

    public MessageQueryService(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Override
    public List<MessageExport> exportOf(long userId) {
        return messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                        .eq(Message::getUserId, userId)
                        .orderByAsc(Message::getId))
                .stream()
                .map(message -> new MessageExport(
                        String.valueOf(message.getKind()), String.valueOf(message.getType()),
                        message.getTitle(), message.getContent(), message.getRiskLevel(),
                        message.getRemindAt(), message.getStatus() == 2, message.getCreatedAt()))
                .toList();
    }
}
