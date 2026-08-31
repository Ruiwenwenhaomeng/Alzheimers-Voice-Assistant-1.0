package com.alz.service;

import com.alz.entity.AudioRecord;
import com.alz.entity.AudioFullInfo;
import java.util.List;

public interface AudioService {
    void save(Long userId, String path, Integer duration, String imageName);
    void save(Long userId, String path, Integer duration, String imageName,
              String consentVersion, String taskType);
    List<AudioRecord> listAll();
    void delete(Long id);
    List<AudioFullInfo> listFull(String username, String name);
    // 新增方法：根据用户 ID 查询音频列表
    List<AudioRecord> listByUser(Long userId);
    String getAudioFilePath(String audioId);
    boolean belongsToUser(String filePath, Long userId);
    boolean deleteOwned(String filePath, Long userId);
    AudioRecord findOwnedById(Long id, Long userId);
    AudioRecord findOwnedByFilePath(String filePath, Long userId);
}
