package de.hda.fbi.efussgaengerzone.domain.usecase;

import de.hda.fbi.efussgaengerzone.domain.model.appointment.Appointment;
import de.hda.fbi.efussgaengerzone.domain.model.appointment.AppointmentFilter;
import de.hda.fbi.efussgaengerzone.domain.model.appointment.AppointmentFilterFuture;
import de.hda.fbi.efussgaengerzone.domain.model.appointment.AppointmentRepository;
import de.hda.fbi.efussgaengerzone.domain.model.shop.OpeningHours;
import de.hda.fbi.efussgaengerzone.domain.model.shop.Shop;
import de.hda.fbi.efussgaengerzone.domain.model.shop.ShopRepository;
import de.hda.fbi.efussgaengerzone.domain.model.shop.WeeklyOpeningHours;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AppointmentScheduling {
    private static final Logger LOG = LoggerFactory.getLogger(AppointmentScheduling.class);
    private static final Comparator<Appointment> APPOINTMENT_COMPARATOR = Comparator.comparing(Appointment::dateTime);

    private final AppointmentRepository appointmentRepository;
    private final ShopRepository shopRepository;

    public AppointmentScheduling(AppointmentRepository appointmentRepository, ShopRepository shopRepository) {
        this.appointmentRepository = appointmentRepository;
        this.shopRepository = shopRepository;
    }

    public void makeAppointment(UUID shopId, Appointment appointment) {
        LOG.info("Making appointment for shopId {} with details {}", shopId, appointment);
        appointmentRepository.save(shopId, appointment);
        LOG.info("Appointment made successfully for shopId {}", shopId);
    }

    public void deleteAppointment(UUID shopId, UUID appointmentId) {
        LOG.info("Deleting appointment with id {} for shopId {}", appointmentId, shopId);
        appointmentRepository.delete(shopId, appointmentId);
        LOG.info("Appointment with id {} deleted successfully for shopId {}", appointmentId, shopId);
    }

    public Optional<Appointment> findNextAppointment(UUID shopId) {
        LOG.info("Looking up next appointment for shopId {}", shopId);
        return searchAppointments(shopId, Set
                .of(AppointmentFilterFuture.INSTANCE))
                .stream()
                .min(APPOINTMENT_COMPARATOR);
    }

    public Collection<Appointment> searchAppointments(UUID shopId, Set<AppointmentFilter> filters) {
        LOG.info("Searching appointments for shopId {} with filters {}", shopId, filters);
        return shopRepository
                .findById(shopId)
                .map(shop -> appointmentRepository
                        .findForShopId(shopId)
                        .stream()
                        .filter(appointment -> filters
                                .stream()
                                .allMatch(filter -> filter.test(appointment)))
                        .toList())
                .orElseGet(List::of);
    }


    private OpeningHours getOpeningHoursForDay(WeeklyOpeningHours weeklyOpeningHours, DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> weeklyOpeningHours.monday();
            case TUESDAY -> weeklyOpeningHours.tuesday();
            case WEDNESDAY -> weeklyOpeningHours.wednesday();
            case THURSDAY -> weeklyOpeningHours.thursday();
            case FRIDAY -> weeklyOpeningHours.friday();
            case SATURDAY -> weeklyOpeningHours.saturday();
            default -> null;
        };
    }

    private List<LocalTime> calculateAllPossibleTimes(OpeningHours openingHours, int appointmentDurationMinutes) {
        List<LocalTime> allPossibleTimes = new ArrayList<>();
        LocalTime currentStartTime = openingHours.openingTime();

        // Berechnet alle möglichen Terminzeiten basierend auf den Öffnungszeiten und der Dauer eines Termins
        while (currentStartTime.plusMinutes(appointmentDurationMinutes).isBefore(openingHours.closingTime()) ||
                currentStartTime.plusMinutes(appointmentDurationMinutes).equals(openingHours.closingTime())) {
            allPossibleTimes.add(currentStartTime);
            currentStartTime = currentStartTime.plusMinutes(appointmentDurationMinutes);
        }
        return allPossibleTimes;
    }

    private List<Appointment> findAppointmentsOnDay(UUID shopId, DayOfWeek dayOfWeek) {
        return appointmentRepository.findForShopId(shopId)
                .stream()
                .filter(appointment -> appointment.dateTime().getDayOfWeek() == dayOfWeek)
                .toList();
    }

    private List<LocalTime> calculateAvailableTimes(List<LocalTime> allPossibleTimes, List<Appointment> appointmentsOnDay) {
        Set<LocalTime> bookedTimes = appointmentsOnDay.stream()
                .map(appointment -> appointment.dateTime().toLocalTime())
                .collect(Collectors.toSet());

        return allPossibleTimes.stream()
                .filter(time -> !bookedTimes.contains(time))
                .toList();
    }

    public List<LocalTime> availableDatesOnDay(UUID shopId, DayOfWeek dayOfWeek) throws ShopNotFoundException {
        LOG.info("Checking available dates on day {} for shopId {}", dayOfWeek, shopId);

        Shop shop = shopRepository.findById(shopId).orElseThrow(() -> {
            LOG.error("Shop not found for id {}", shopId);
            return new ShopNotFoundException(shopId);
        });

        OpeningHours openingHours = getOpeningHoursForDay(shop.weeklyOpeningHours(), dayOfWeek);
        if (openingHours == null) {
            LOG.info("Shop is closed on day {} for shopId {}", dayOfWeek, shopId);
            return List.of();
        }

        List<LocalTime> allPossibleTimes = calculateAllPossibleTimes(openingHours, shop.minsPerCustomer());

        List<Appointment> appointmentsOnDay = findAppointmentsOnDay(shopId, dayOfWeek);

        List<LocalTime> availableTimes = calculateAvailableTimes(allPossibleTimes, appointmentsOnDay);

        LOG.info("Found {} available dates for shopId {}", availableTimes.size(), shopId);
        return availableTimes;
    }

}
