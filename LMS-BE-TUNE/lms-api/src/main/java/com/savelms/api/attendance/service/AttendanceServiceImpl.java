package com.savelms.api.attendance.service;

import com.savelms.api.vacation.dto.AddVacationRequest;
import com.savelms.api.vacation.dto.UseVacationRequest;
import com.savelms.api.vacation.service.VacationService;
import com.savelms.core.attendance.domain.AttendanceStatus;
import com.savelms.core.attendance.domain.entity.Attendance;
import com.savelms.core.attendance.domain.repository.AttendanceRepository;
import com.savelms.core.attendance.dto.AttendanceDto;
import com.savelms.core.calendar.domain.repository.CalendarRepository;
import com.savelms.core.exception.NoPermissionException;
import com.savelms.core.statistical.DayStatisticalData;
import com.savelms.core.statistical.DayStatisticalDataRepository;
import com.savelms.core.user.AttendStatus;
import com.savelms.core.user.domain.entity.User;
import com.savelms.core.user.domain.repository.UserRepository;
import com.savelms.core.user.role.RoleEnum;
import com.savelms.core.user.role.domain.entity.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.savelms.core.attendance.domain.AttendanceStatus.*;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AttendanceServiceImpl implements AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final DayStatisticalDataRepository statisticalDataRepository;
    private final UserRepository userRepository;
    private final VacationService vacationService;
    private final CalendarRepository calendarRepository;
    private static Double day = 0.5D;

    @Override
    @Transactional
    public void checkIn(Long attendanceId, String apiId, AttendanceStatus status) throws NoPermissionException {

        // attendanceId 로 변경하는 유저 찾고 그 유저의 정보 변경하기

        Optional<Attendance> findAttendanceOptional = attendanceRepository.findById(attendanceId);

        Optional<User> user = userRepository.findByApiId(apiId);            // 변경 권한 확인하기

        /**
         *  휴가 상태 체크
         *
         */
        if (findAttendanceOptional.get().getCheckInStatus().equals(VACATION)) {
            vacationService.addVacation(
                    new AddVacationRequest(day),
                    findAttendanceOptional
                            .get()
                            .getUser()
                            .getApiId());
        }
        /**
         *  체크인 된 상태 체크
         */
        findAttendanceOptional
                .ifPresent(allUser -> {
                    allUser.setCheckInStatus(status);
                    attendanceRepository.save(allUser);
                });
        Stream<Attendance> checkInAndOutStatus = attendanceRepository.findAttendanceByUserId(findAttendanceOptional.get().getUser().getId());



        final LocalDate today = findAttendanceOptional.get().getCalendar().getDate();
        List<AttendanceStatus> checkInList = checkStatusList(checkInAndOutStatus, today, today.getMonth(), NONE);


        final LocalDate date = LocalDate.now().with(TemporalAdjusters.previous(DayOfWeek.SATURDAY));

        List<AttendanceStatus> checkInList2 = checkStatusList(checkInAndOutStatus, date, today.getMonth(), ABSENT);





        double score = 0;
        double participateScore = 0;
        double weekAbsentScore = 0;
        for (AttendanceStatus a : checkInList) {
            if (a == TARDY) {
                score += TARDY.getAttendancePenalty();      // 점수 enum으로 변경
            } else if (a == ABSENT) {
                score += ABSENT.getAttendancePenalty();
            } else if (a == PRESENT) {
                participateScore += PRESENT.getAttendanceScore();
            }
        }
        weekAbsentScore = checkInList2.size() * 0.5;

        double result = score;
        double participateResult = participateScore;
        if (status == VACATION) {
            vacationService.useVacation(new UseVacationRequest(day, "휴가"), apiId);
        }
        long noOfDaysBetween = ChronoUnit.DAYS.between(findAttendanceOptional.get().getCalendar().getDate(), LocalDate.now());
        Long calendarId = findAttendanceOptional.get().getCalendar().getId();
        for (int i = 0; i < noOfDaysBetween + 1; i++) {
            Optional<DayStatisticalData> change = statisticalDataRepository.findAllByUser_idAndCalendar_id(findAttendanceOptional.get().getUser().getId(), calendarId);
            Optional<Attendance> attendStatus = attendanceRepository.findByUserIdAndCalendarId(findAttendanceOptional.get().getUser().getId(), calendarId);
            if (i != 0) {
                if (attendStatus.get().getCheckInStatus() == TARDY) {
                    score += TARDY.getAttendancePenalty();
                } else if (attendStatus.get().getCheckInStatus() == ABSENT) {
                    score += ABSENT.getAttendancePenalty();
                } else if (attendStatus.get().getCheckInStatus() == PRESENT) {
                    participateScore += PRESENT.getAttendanceScore();
                }
                if (attendStatus.get().getCheckOutStatus() == TARDY) {
                    score += TARDY.getAttendancePenalty();
                } else if (attendStatus.get().getCheckOutStatus() == ABSENT) {
                    score += ABSENT.getAttendancePenalty();
                } else if (attendStatus.get().getCheckOutStatus() == PRESENT) {
                    participateScore += PRESENT.getAttendanceScore();
                }
            }
            result = score;
            participateResult = participateScore;
            double finalResult = result;
            double finalParticipateResult = participateResult;
            double finalWeekAbsentScore = weekAbsentScore;
            change.ifPresent(userInfo -> {
                userInfo.setAbsentScore(finalResult);
                userInfo.setAttendanceScore(finalParticipateResult);
                userInfo.setWeekAbsentScore(finalWeekAbsentScore);
                userInfo.setTotalScore(finalResult - userInfo.getStudyTimeScore());
                statisticalDataRepository.save(userInfo);
            });
            calendarId++;
        }
    }

    @Override
    @Transactional
    public void checkOut(Long attendanceId, String userApiId, AttendanceStatus status) throws NoPermissionException {

        Optional<Attendance> findAttendanceOptional = attendanceRepository.findById(attendanceId);
        Optional<User> user = userRepository.findByApiId(userApiId);            // 변경 권한 확인하기

        if (findAttendanceOptional.get().getCheckOutStatus().equals(VACATION)) {
            vacationService.addVacation(
                    new AddVacationRequest(day),
                    findAttendanceOptional
                            .get()
                            .getUser()
                            .getApiId());
        }
        findAttendanceOptional
                .ifPresent(allUser -> {
                    allUser.setCheckOutStatus(status);
                    attendanceRepository.save(allUser);
                });
        Stream<Attendance> checkInAndOutStatus = attendanceRepository.findAttendanceByUserId(findAttendanceOptional.get().getUser().getId());

        final LocalDate today = findAttendanceOptional.get().getCalendar().getDate();
        List<AttendanceStatus> checkInList = checkStatusList(checkInAndOutStatus, today, today.getMonth(), NONE);


        final LocalDate date = LocalDate.now().with(TemporalAdjusters.previous(DayOfWeek.SATURDAY));

        List<AttendanceStatus> checkInList2 = checkStatusList(checkInAndOutStatus, date, today.getMonth(), ABSENT);




        double score = 0;
        double participateScore = 0;
        double weekAbsentScore = 0;
        for (AttendanceStatus a : checkInList) {
            if (a == TARDY) {
                score += TARDY.getAttendancePenalty();
            } else if (a == ABSENT) {
                score += ABSENT.getAttendancePenalty();
            } else if (a == PRESENT) {
                participateScore += PRESENT.getAttendanceScore();
            }
        }
        weekAbsentScore = checkInList2.size() * 0.5;

        double result = score;
        double participateResult = participateScore;

        if (status == VACATION) {
            vacationService.useVacation(new UseVacationRequest(day, "휴가"), userApiId);
        }

        long noOfDaysBetween = ChronoUnit.DAYS.between(findAttendanceOptional.get().getCalendar().getDate(), LocalDate.now());

        Long calendarId = findAttendanceOptional.get().getCalendar().getId();
        for (int i = 0; i < noOfDaysBetween + 1; i++) {
            Optional<DayStatisticalData> change = statisticalDataRepository.findAllByUser_idAndCalendar_id(findAttendanceOptional.get().getUser().getId(), calendarId);
            Optional<Attendance> attendStatus = attendanceRepository.findByUserIdAndCalendarId(findAttendanceOptional.get().getUser().getId(), calendarId);
            if (i != 0) {
                if (attendStatus.get().getCheckInStatus() == TARDY) {
                    score += TARDY.getAttendancePenalty();
                } else if (attendStatus.get().getCheckInStatus() == ABSENT) {
                    score += ABSENT.getAttendancePenalty();
                } else if (attendStatus.get().getCheckInStatus() == PRESENT) {
                    participateScore += PRESENT.getAttendanceScore();
                }
                if (attendStatus.get().getCheckOutStatus() == TARDY) {
                    score += TARDY.getAttendancePenalty();
                } else if (attendStatus.get().getCheckOutStatus() == ABSENT) {
                    score += ABSENT.getAttendancePenalty();
                } else if (attendStatus.get().getCheckOutStatus() == PRESENT) {
                    participateScore += PRESENT.getAttendanceScore();
                }
            }
            result = score;
            participateResult = participateScore;
            double finalResult = result;
            double finalParticipateResult = participateResult;
            double finalWeekAbsentScore = weekAbsentScore;
            change.ifPresent(userInfo -> {
                userInfo.setAbsentScore(finalResult);
                userInfo.setAttendanceScore(finalParticipateResult);
                userInfo.setWeekAbsentScore(finalWeekAbsentScore);
                userInfo.setTotalScore(finalResult - userInfo.getStudyTimeScore());
                statisticalDataRepository.save(userInfo);
            });
            calendarId++;
        }
    }

    public  List<AttendanceStatus> checkStatusList(Stream<Attendance> checkInAndOutStatus, LocalDate date, Month month, AttendanceStatus status) {
        List<AttendanceStatus> checkOutList2 = checkInAndOutStatus
                .filter(x -> x.getCheckOutStatus() == status
                        && x.getCalendar().getDate().getMonth().equals(month)
                        && x.getCalendar().getDate().isAfter(date))
                .map(Attendance::getCheckOutStatus)
                .collect(Collectors.toList());

        List<AttendanceStatus> checkInList2  = checkInAndOutStatus
                .filter(x -> x.getCheckInStatus() == status
                        && x.getCalendar().getDate().getMonth().equals(month)
                        && x.getCalendar().getDate().isAfter(date))
                .map(Attendance::getCheckInStatus)
                .collect(Collectors.toList());
        checkInList2.addAll(checkOutList2);
        return checkInList2;
    }

    public Map<Long, Attendance> getAllAttendanceByDateAndAttendStatus(LocalDate date, AttendStatus attendStatus) {

        return attendanceRepository.findAllByDateAndAttendStatusWithUser(date, attendStatus)
                .stream()
                .collect(Collectors.toMap(
                        attendance -> attendance.getUser().getId(),
                        attendance -> attendance
                ));
    }

    public Map<Long, AttendanceDto> getAllAttendanceByDate(LocalDate date) {


        List<Attendance> list = attendanceRepository.findByCalendarId(calendarRepository.findAllByDate(date).getId());
        Map<Long, AttendanceDto> map = new HashMap<>();

        for (int i = 0; i < list.size(); i++) {
            map.put(list.get(i).getId(), new AttendanceDto(list.get(i).getUser().getApiId(), list.get(i).getId(), list.get(i).getCheckInStatus(), list.get(i).getCheckOutStatus()));
        }

        return map;
    }
}
