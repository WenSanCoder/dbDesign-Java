package com.zjut.edusystem.selection;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class ReservedSelectionQueueService implements SelectionQueueService {
    @Override
    public String acceptSelectionRequest(Long studentId, Long teachingClassId, Long roundId) {
        return "REQ-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())
                + "-" + studentId + "-" + teachingClassId + "-" + roundId;
    }
}
