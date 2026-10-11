package com.yuchen.portfolio.repository;

import com.yuchen.portfolio.entity.MediaTag;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaTagRepository extends JpaRepository<MediaTag, Long> {

  Optional<MediaTag> findByName(String name);

  List<MediaTag> findByNameIn(Collection<String> names);

  List<MediaTag> findAllByOrderByNameAsc();
}
