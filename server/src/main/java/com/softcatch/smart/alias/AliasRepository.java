package com.softcatch.smart.alias;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AliasRepository extends JpaRepository<Alias, Long> {

  List<Alias> findByMemberIdOrderByIdAsc(Long memberId);
}
