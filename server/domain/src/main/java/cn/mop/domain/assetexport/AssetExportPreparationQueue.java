package cn.mop.domain.assetexport;

import cn.mop.domain.assetexport.AssetExportJobRepository.Claim;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Prospective, preparation-only consumption. Never claims a file that already has prepared bytes. */
public interface AssetExportPreparationQueue {
    Optional<Claim> claimNextUnprepared(String worker,Duration lease,Instant acceptedAfter);
    /** Only a confirmed technical input limit may terminate an unprepared job here. */
    void rejectPreparationInputLimit(Claim claim);
}
