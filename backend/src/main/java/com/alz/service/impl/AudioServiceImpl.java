package com.alz.service.impl;

import com.alz.config.StoragePaths;
import com.alz.entity.AudioFullInfo;
import com.alz.entity.AudioRecord;
import com.alz.entity.User;
import com.alz.mapper.AudioMapper;
import com.alz.mapper.UserMapper;
import com.alz.service.AudioService;
import com.alz.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AudioServiceImpl implements AudioService {

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private StoragePaths storagePaths;

    @Override
    public void save(Long userId, String path, Integer duration, String imageName) {
        save(userId, path, duration, imageName, null, null);
    }

    @Override
    public void save(Long userId, String path, Integer duration, String imageName,
                     String consentVersion, String taskType) {
        AudioRecord record = new AudioRecord();
        record.setUserId(userId);
        record.setFilePath(path);
        record.setDuration(duration);
        record.setImageName(imageName);
        record.setConsentVersion(consentVersion);
        record.setTaskType(taskType);
        audioMapper.insert(record);
    }

    @Override
    public List<AudioRecord> listAll() {
        return audioMapper.listAll();
    }

    @Override
    public void delete(Long id) {
        audioMapper.deleteById(id);
    }

    @Override
    public List<AudioFullInfo> listFull(String username, String name) {
        return audioMapper.listFull(username, name);
    }

    // 新增实现
    @Override
    public List<AudioRecord> listByUser(Long userId) {
        return audioMapper.findByUserId(userId);
    }

    @Override
    public String getAudioFilePath(String audioId) {
        return storagePaths.resolveAudio(audioId).toString();
    }

    @Override
    public boolean belongsToUser(String filePath, Long userId) {
        if (filePath == null || filePath.isBlank() || userId == null) {
            return false;
        }
        Long count = audioMapper.countByFilePathAndUserId(filePath, userId);
        return count != null && count > 0;
    }

    @Override
    public boolean deleteOwned(String filePath, Long userId) {
        if (filePath == null || filePath.isBlank() || userId == null) {
            return false;
        }
        return audioMapper.deleteByFilePathAndUserId(filePath, userId) > 0;
    }

    @Override
    public AudioRecord findOwnedById(Long id, Long userId) {
        if (id == null || userId == null) {
            return null;
        }
        return audioMapper.findOwnedById(id, userId);
    }

    @Override
    public AudioRecord findOwnedByFilePath(String filePath, Long userId) {
        if (filePath == null || filePath.isBlank() || userId == null) {
            return null;
        }
        return audioMapper.findOwnedByFilePath(filePath, userId);
    }
}
