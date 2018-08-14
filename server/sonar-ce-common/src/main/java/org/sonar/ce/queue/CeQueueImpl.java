/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.ce.queue;

import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.log.Loggers;
import org.sonar.ce.task.CeTask;
import org.sonar.core.util.UuidFactory;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.ce.CeActivityDto;
import org.sonar.db.ce.CeQueueDto;
import org.sonar.db.ce.CeTaskCharacteristicDto;
import org.sonar.db.ce.DeleteIf;
import org.sonar.db.component.ComponentDto;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.property.InternalProperties;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.singleton;
import static org.sonar.ce.queue.CeQueue.SubmitOption.UNIQUE_QUEUE_PER_COMPONENT;
import static org.sonar.core.util.stream.MoreCollectors.toEnumSet;
import static org.sonar.core.util.stream.MoreCollectors.uniqueIndex;
import static org.sonar.db.ce.CeQueueDto.Status.PENDING;

@ServerSide
public class CeQueueImpl implements CeQueue {

  private final DbClient dbClient;
  private final UuidFactory uuidFactory;
  private final DefaultOrganizationProvider defaultOrganizationProvider;

  public CeQueueImpl(DbClient dbClient, UuidFactory uuidFactory, DefaultOrganizationProvider defaultOrganizationProvider) {
    this.dbClient = dbClient;
    this.uuidFactory = uuidFactory;
    this.defaultOrganizationProvider = defaultOrganizationProvider;
  }

  @Override
  public CeTaskSubmit.Builder prepareSubmit() {
    return new CeTaskSubmit.Builder(uuidFactory.create());
  }

  @Override
  public CeTask submit(CeTaskSubmit submission) {
    return submit(submission, EnumSet.noneOf(SubmitOption.class)).get();
  }

  @Override
  public java.util.Optional<CeTask> submit(CeTaskSubmit submission, SubmitOption... options) {
    return submit(submission, toSet(options));
  }

  private java.util.Optional<CeTask> submit(CeTaskSubmit submission, EnumSet<SubmitOption> submitOptions) {
    try (DbSession dbSession = dbClient.openSession(false)) {
      if (submitOptions.contains(UNIQUE_QUEUE_PER_COMPONENT)
        && submission.getComponentUuid() != null
        && dbClient.ceQueueDao().countByStatusAndComponentUuid(dbSession, PENDING, submission.getComponentUuid()) > 0) {
        return java.util.Optional.empty();
      }
      CeQueueDto taskDto = addToQueueInDb(dbSession, submission);
      dbSession.commit();

      ComponentDto component = null;
      String componentUuid = taskDto.getComponentUuid();
      if (componentUuid != null) {
        component = dbClient.componentDao().selectByUuid(dbSession, componentUuid).orNull();
      }
      CeTask task = convertToTask(taskDto, submission.getCharacteristics(), component);
      return java.util.Optional.of(task);
    }
  }

  @Override
  public List<CeTask> massSubmit(Collection<CeTaskSubmit> submissions, SubmitOption... options) {
    if (submissions.isEmpty()) {
      return Collections.emptyList();
    }

    try (DbSession dbSession = dbClient.openSession(false)) {
      List<CeQueueDto> taskDto = submissions.stream()
        .filter(filterBySubmitOptions(options, submissions, dbSession))
        .map(submission -> addToQueueInDb(dbSession, submission))
        .collect(Collectors.toList());
      List<CeTask> tasks = loadTasks(dbSession, taskDto);
      dbSession.commit();
      return tasks;
    }
  }

  private Predicate<CeTaskSubmit> filterBySubmitOptions(SubmitOption[] options, Collection<CeTaskSubmit> submissions, DbSession dbSession) {
    EnumSet<SubmitOption> submitOptions = toSet(options);

    if (submitOptions.contains(UNIQUE_QUEUE_PER_COMPONENT)) {
      Set<String> componentUuids = submissions.stream()
        .map(CeTaskSubmit::getComponentUuid)
        .filter(Objects::nonNull)
        .collect(MoreCollectors.toSet(submissions.size()));
      if (componentUuids.isEmpty()) {
        return t -> true;
      }
      return new NoPendingTaskFilter(dbSession, componentUuids);
    }

    return t -> true;
  }

  private class NoPendingTaskFilter implements Predicate<CeTaskSubmit> {
    private final Map<String, Integer> queuedItemsByComponentUuid;

    private NoPendingTaskFilter(DbSession dbSession, Set<String> componentUuids) {
      queuedItemsByComponentUuid = dbClient.ceQueueDao().countByStatusAndComponentUuids(dbSession, PENDING, componentUuids);
    }

    @Override
    public boolean test(CeTaskSubmit ceTaskSubmit) {
      String componentUuid = ceTaskSubmit.getComponentUuid();
      return componentUuid == null || queuedItemsByComponentUuid.getOrDefault(componentUuid, 0) == 0;
    }
  }

  private static EnumSet<SubmitOption> toSet(SubmitOption[] options) {
    return Arrays.stream(options).collect(toEnumSet(SubmitOption.class));
  }

  private CeQueueDto addToQueueInDb(DbSession dbSession, CeTaskSubmit submission) {
    for (Map.Entry<String, String> characteristic : submission.getCharacteristics().entrySet()) {
      CeTaskCharacteristicDto characteristicDto = new CeTaskCharacteristicDto();
      characteristicDto.setUuid(uuidFactory.create());
      characteristicDto.setTaskUuid(submission.getUuid());
      characteristicDto.setKey(characteristic.getKey());
      characteristicDto.setValue(characteristic.getValue());
      dbClient.ceTaskCharacteristicsDao().insert(dbSession, characteristicDto);
    }

    CeQueueDto dto = new CeQueueDto();
    dto.setUuid(submission.getUuid());
    dto.setTaskType(submission.getType());
    dto.setComponentUuid(submission.getComponentUuid());
    dto.setStatus(PENDING);
    dto.setSubmitterUuid(submission.getSubmitterUuid());
    dbClient.ceQueueDao().insert(dbSession, dto);

    return dto;
  }

  private List<CeTask> loadTasks(DbSession dbSession, List<CeQueueDto> dtos) {
    // load components, if defined
    Set<String> componentUuids = dtos.stream()
      .map(CeQueueDto::getComponentUuid)
      .filter(Objects::nonNull)
      .collect(Collectors.toSet());
    Map<String, ComponentDto> componentsByUuid = dbClient.componentDao()
      .selectByUuids(dbSession, componentUuids).stream()
      .collect(uniqueIndex(ComponentDto::uuid));

    // load characteristics
    // TODO could be avoided, characteristics are already present in submissions
    Set<String> taskUuids = dtos.stream().map(CeQueueDto::getUuid).collect(MoreCollectors.toSet(dtos.size()));
    Multimap<String, CeTaskCharacteristicDto> characteristicsByTaskUuid = dbClient.ceTaskCharacteristicsDao()
      .selectByTaskUuids(dbSession, taskUuids).stream()
      .collect(MoreCollectors.index(CeTaskCharacteristicDto::getTaskUuid));

    List<CeTask> result = new ArrayList<>();
    for (CeQueueDto dto : dtos) {
      ComponentDto component = null;
      if (dto.getComponentUuid() != null) {
        component = componentsByUuid.get(dto.getComponentUuid());
      }
      Map<String, String> characteristics = characteristicsByTaskUuid.get(dto.getUuid()).stream()
        .collect(uniqueIndex(CeTaskCharacteristicDto::getKey, CeTaskCharacteristicDto::getValue));
      result.add(convertToTask(dto, characteristics, component));
    }
    return result;
  }

  @Override
  public void cancel(DbSession dbSession, CeQueueDto ceQueueDto) {
    checkState(PENDING.equals(ceQueueDto.getStatus()), "Task is in progress and can't be canceled [uuid=%s]", ceQueueDto.getUuid());
    cancelImpl(dbSession, ceQueueDto);
  }

  private void cancelImpl(DbSession dbSession, CeQueueDto q) {
    CeActivityDto activityDto = new CeActivityDto(q);
    activityDto.setStatus(CeActivityDto.Status.CANCELED);
    remove(dbSession, q, activityDto);
  }

  protected void remove(DbSession dbSession, CeQueueDto queueDto, CeActivityDto activityDto) {
    String taskUuid = queueDto.getUuid();
    CeQueueDto.Status expectedQueueDtoStatus = queueDto.getStatus();

    dbClient.ceActivityDao().insert(dbSession, activityDto);
    dbClient.ceTaskInputDao().deleteByUuids(dbSession, singleton(taskUuid));
    int deletedTasks = dbClient.ceQueueDao().deleteByUuid(dbSession, taskUuid, new DeleteIf(expectedQueueDtoStatus));

    if (deletedTasks == 1) {
      dbSession.commit();
    } else {
      Loggers.get(CeQueueImpl.class).debug(
        "Remove rolled back because task in queue with uuid {} and status {} could not be deleted",
        taskUuid, expectedQueueDtoStatus);
      dbSession.rollback();
    }
  }

  @Override
  public int cancelAll() {
    return cancelAll(false);
  }

  int cancelAll(boolean includeInProgress) {
    int count = 0;
    try (DbSession dbSession = dbClient.openSession(false)) {
      for (CeQueueDto queueDto : dbClient.ceQueueDao().selectAllInAscOrder(dbSession)) {
        if (includeInProgress || !queueDto.getStatus().equals(CeQueueDto.Status.IN_PROGRESS)) {
          cancelImpl(dbSession, queueDto);
          count++;
        }
      }
      return count;
    }
  }

  @Override
  public void pauseWorkers() {
    try (DbSession dbSession = dbClient.openSession(false)) {
      dbClient.internalPropertiesDao().save(dbSession, InternalProperties.COMPUTE_ENGINE_PAUSE, "true");
      dbSession.commit();
    }
  }

  @Override
  public void resumeWorkers() {
    try (DbSession dbSession = dbClient.openSession(false)) {
      dbClient.internalPropertiesDao().delete(dbSession, InternalProperties.COMPUTE_ENGINE_PAUSE);
      dbSession.commit();
    }
  }

  @Override
  public WorkersPauseStatus getWorkersPauseStatus() {
    try (DbSession dbSession = dbClient.openSession(false)) {
      java.util.Optional<String> propValue = dbClient.internalPropertiesDao().selectByKey(dbSession, InternalProperties.COMPUTE_ENGINE_PAUSE);
      if (!propValue.isPresent() || !propValue.get().equals("true")) {
        return WorkersPauseStatus.RESUMED;
      }
      int countInProgress = dbClient.ceQueueDao().countByStatus(dbSession, CeQueueDto.Status.IN_PROGRESS);
      if (countInProgress > 0) {
        return WorkersPauseStatus.PAUSING;
      }
      return WorkersPauseStatus.PAUSED;
    }
  }

  CeTask convertToTask(CeQueueDto taskDto, Map<String, String> characteristics, @Nullable ComponentDto component) {
    CeTask.Builder builder = new CeTask.Builder()
      .setUuid(taskDto.getUuid())
      .setType(taskDto.getTaskType())
      .setSubmitterUuid(taskDto.getSubmitterUuid())
      .setComponentUuid(taskDto.getComponentUuid())
      .setCharacteristics(characteristics);

    if (component != null) {
      builder.setOrganizationUuid(component.getOrganizationUuid());
      builder.setComponentKey(component.getDbKey());
      builder.setComponentName(component.name());
    }

    // FIXME this should be set from the CeQueueDto
    if (!builder.hasOrganizationUuid()) {
      builder.setOrganizationUuid(defaultOrganizationProvider.get().getUuid());
    }

    return builder.build();
  }

}